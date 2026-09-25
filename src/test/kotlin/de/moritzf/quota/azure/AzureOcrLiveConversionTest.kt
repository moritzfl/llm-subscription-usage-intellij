package de.moritzf.quota.azure

import de.moritzf.quota.mistral.MistralOcrWriteResult
import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.DocumentImageFormat
import de.moritzf.quota.shared.DocumentImageOptions
import de.moritzf.quota.shared.DocumentMarkdownWriteResult
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.multipdf.PageExtractor
import org.apache.batik.transcoder.TranscoderInput
import org.apache.batik.transcoder.TranscoderOutput
import org.apache.batik.transcoder.image.PNGTranscoder
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/** Opt-in paid integration test. Uses only the user's documented Azure CLI login. */
@EnabledIfEnvironmentVariable(named = "AZURE_OCR_LIVE", matches = "true")
class AzureOcrLiveConversionTest {
    @Test
    fun convertsLongPdfWithValidFigureLinksAndCompleteProgress() {
        val source = Path.of(requireNotNull(System.getenv("AZURE_OCR_LIVE_INPUT")))
        val output = Path.of(requireNotNull(System.getenv("AZURE_OCR_LIVE_OUTPUT"))).toAbsolutePath()
        val resource = requireNotNull(System.getenv("AZURE_OCR_LIVE_RESOURCE"))
        val model = requireNotNull(System.getenv("AZURE_OCR_LIVE_MODEL"))
        val documentUrl = System.getenv("AZURE_OCR_LIVE_URL")
        val (pageCount, sourceText) = Loader.loadPDF(source.toFile()).use { it.numberOfPages to PDFTextStripper().getText(it) }
        assertTrue(pageCount > 30, "Use a long PDF to exercise multiple real requests")
        val completed = mutableListOf<Int>()
        val started = System.nanoTime()
        val replayOnly = System.getenv("AZURE_OCR_REPLAY") == "true"
        val replayCli = AzureCli(Path.of("/test/az"), run = { _, _, _, _ ->
            """{"accessToken":"replay-token","expires_on":4102444800}"""
        })
        val cli = if (replayOnly) replayCli else AzureCli(assertNotNull(AzureCli.findExecutable()))
        val config = azureAccountConfig(System.getenv("AZURE_OCR_LIVE_SUBSCRIPTION"), resource, null, null, null)
        val responses = mutableListOf<String>()
        Files.createDirectories(output.parent)
        val client = HttpClient.newHttpClient()
        val liveClient = AzureOcrClient(post = { request ->
            val response = if (replayOnly) AzureOcrResponse(200,
                Files.readString(output.resolveSibling("${output.fileName}-response-${responses.size + 1}.json")))
            else client.send(request, HttpResponse.BodyHandlers.ofString()).let { AzureOcrResponse(it.statusCode(), it.body()) }
            if (response.status in 200..299) {
                responses += response.body
                // Public test-document responses only, no request headers or credentials.
                if (!replayOnly) Files.writeString(output.resolveSibling("${output.fileName}-response-${responses.size}.json"), response.body)
            }
            response
        })
        val result = JsonSupport.json.decodeFromString<MistralOcrWriteResult>(liveClient.convertDocument(
            cli, config,
            model, documentUrl = documentUrl, localFile = if (documentUrl == null) source else null, outputFile = output,
            progress = { done, total, detail ->
                if (total > 0 && completed.lastOrNull() != done) {
                    completed += done
                    println("Azure OCR: $done/$total — $detail")
                }
            },
        ))
        client.close()
        assertEquals(pageCount, result.pages)
        assertEquals(pageCount, completed.last())
        assertEquals(completed.sorted(), completed)
        assertEquals(result.imageFiles.size, result.imageFiles.distinct().size)
        val markdown = Files.readString(output)
        assertTrue(markdown.isNotBlank())
        result.imageFiles.forEach { value ->
            val image = Path.of(value)
            assertTrue(Files.size(image) > 0)
            if (value.endsWith(".svg")) {
                val xml = Files.readString(image)
                assertTrue(xml.contains("<path") || xml.contains("<rect"), "SVG has no vector drawing")
                val width = Regex("<svg[^>]*\\swidth=\"([0-9.]+)\"").find(xml)!!.groupValues[1].toFloat()
                val preview = ByteArrayOutputStream().use { bytes ->
                    PNGTranscoder().apply { addTranscodingHint(PNGTranscoder.KEY_WIDTH, width * 300f / 72f) }
                        .transcode(TranscoderInput(image.toUri().toString()), TranscoderOutput(bytes))
                    bytes.toByteArray()
                }
                Files.write(image.resolveSibling(image.fileName.toString() + ".preview.png"), preview)
                assertNotNull(ImageIO.read(preview.inputStream()), "SVG preview failed: $image")
                println("SVG figure: ${image.fileName}; embedded raster=${xml.contains("data:image")}")
            } else assertNotNull(ImageIO.read(image.toFile()), "Unreadable image: $image")
            val link = URI(null, null, "${image.parent.fileName}/${image.fileName}", null).toASCIIString()
                .replace("(", "%28").replace(")", "%29")
            assertTrue(markdown.contains(link), "Image not linked: $image")
        }
        val sourceWords = words(sourceText)
        val overlap = words(markdown).intersect(sourceWords).size.toDouble() / sourceWords.size.coerceAtLeast(1)
        assertTrue(overlap >= 0.80, "Only $overlap of source vocabulary retained")
        assertTrue(result.imageFiles.any { it.endsWith(".svg") }, "Expected vector export for this PDF; warnings=${result.warnings}")
        // Reuse the identical real responses to compare all export modes without paying for OCR again.
        for (options in listOf(DocumentImageOptions(DocumentImageFormat.PNG, 300),
            DocumentImageOptions(DocumentImageFormat.PNG, 600), DocumentImageOptions(DocumentImageFormat.PROVIDER))) {
            var index = 0
            val destination = output.resolveSibling("${output.fileName.toString().removeSuffix(".md")}-${options.format}-${options.dpi}.md")
            val replay = AzureOcrClient(post = { AzureOcrResponse(200, responses[index++]) })
                .convertDocument(replayCli, config, model, localFile = source, outputFile = destination, imageOptions = options)
            val written = JsonSupport.json.decodeFromString<MistralOcrWriteResult>(replay)
            assertEquals(responses.size, index)
            assertEquals(result.pages, written.pages)
            assertEquals(result.imageFiles.size, written.imageFiles.size)
            assertTrue(written.warnings.isEmpty(), written.warnings.joinToString())
            written.imageFiles.forEach { value ->
                val bitmap = assertNotNull(ImageIO.read(Path.of(value).toFile()))
                println("${options.format}/${options.dpi}: ${Path.of(value).fileName} ${bitmap.width}x${bitmap.height}")
            }
            if (options.format == DocumentImageFormat.PNG && options.dpi == 300) {
                result.imageFiles.zip(written.imageFiles).forEach { (svg, png) ->
                    val vector = assertNotNull(ImageIO.read(Path.of("$svg.preview.png").toFile()))
                    val raster = assertNotNull(ImageIO.read(Path.of(png).toFile()))
                    var error = 0L
                    var count = 0L
                    for (y in 0 until raster.height step 3) for (x in 0 until raster.width step 3) {
                        val actual = vector.getRGB((x.toLong() * vector.width / raster.width).toInt(),
                            (y.toLong() * vector.height / raster.height).toInt())
                        val expected = raster.getRGB(x, y)
                        for (shift in listOf(0, 8, 16)) {
                            error += kotlin.math.abs(((actual shr shift) and 255) - ((expected shr shift) and 255))
                            count++
                        }
                    }
                    val difference = error.toDouble() / (count * 255)
                    println("SVG/PNG mean color difference ${Path.of(svg).fileName}: $difference")
                    assertTrue(difference < 0.065, "SVG color/geometry differs from original PDF: $difference")
                }
            }
        }
        assertTrue(result.warnings.isEmpty(), result.warnings.joinToString())
        println("Azure OCR ${if (replayOnly) "recorded-response replay" else "live"} passed: pages=$pageCount, images=${result.imageFiles.size}, " +
            "source-word coverage=$overlap, seconds=${(System.nanoTime() - started) / 1_000_000_000}, output=$output")
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "AZURE_DI_LIVE", matches = "true")
    fun documentIntelligenceExportsNativeFigureRegions() {
        val source = Path.of(requireNotNull(System.getenv("AZURE_OCR_LIVE_INPUT")))
        val folder = Path.of(requireNotNull(System.getenv("AZURE_OCR_LIVE_OUTPUT"))).toAbsolutePath().parent
        Files.createDirectories(folder)
        val sample = folder.resolve("xrechnung-figures.pdf")
        Loader.loadPDF(source.toFile()).use { document ->
            PageExtractor(document, 36, 37).extract().use { it.save(sample.toFile()) }
        }
        HttpClient.newHttpClient().use { http ->
            val client = AzureDocumentIntelligenceClient(send = { request ->
                val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
                if (request.method() == "GET" && response.statusCode() == 200 &&
                    response.headers().firstValue("content-type").orElse("").contains("json")) {
                    Files.write(folder.resolve("document-intelligence-response.json"), response.body())
                }
                AzureDocumentIntelligenceResponse(response.statusCode(),
                    response.headers().map().mapValues { it.value.firstOrNull().orEmpty() }, response.body())
            })
            val output = folder.resolve("xrechnung-document-intelligence.md")
            val result = JsonSupport.json.decodeFromString<DocumentMarkdownWriteResult>(client.convertDocument(
                AzureCli(assertNotNull(AzureCli.findExecutable())),
                azureAccountConfig(System.getenv("AZURE_OCR_LIVE_SUBSCRIPTION"),
                    requireNotNull(System.getenv("AZURE_OCR_LIVE_RESOURCE")), null, null, null),
                localFile = sample, outputFile = output,
            ))
            assertEquals(2, result.pageCount)
            assertEquals(2, result.imageFiles.size, "Expected one diagram per sample page")
            assertTrue(result.warnings.isEmpty(), result.warnings.joinToString())
            val markdown = Files.readString(output)
            result.imageFiles.forEach { value ->
                assertTrue(value.endsWith(".svg"))
                assertTrue(markdown.contains(Path.of(value).fileName.toString()))
                assertTrue(!Files.readString(Path.of(value)).contains("data:image"))
            }
            println("Document Intelligence live passed: pages=${result.pageCount}, SVG figures=${result.imageFiles.size}, output=$output")
        }
    }

    private fun words(text: String): Set<String> = Regex("[\\p{L}\\p{N}]{4,}").findAll(text.lowercase())
        .map { it.value }.toSet()
}
