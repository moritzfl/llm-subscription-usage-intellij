package de.moritzf.quota.shared

import de.moritzf.quota.azure.*
import de.moritzf.quota.mistral.*
import de.moritzf.quota.zai.ZaiOcrClient
import java.awt.Color
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.apache.batik.transcoder.TranscoderInput
import org.apache.batik.transcoder.TranscoderOutput
import org.apache.batik.transcoder.image.PNGTranscoder
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.junit.jupiter.api.io.TempDir

class OcrFigureCoordinatesTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun mistralUsesOriginalPageAfterChunkOffsetWithRestartedImageIds() {
        val source = pdf(32)
        val output = directory.resolve("mistral.md")
        val result = MistralMarkdownWriter(output, true, originalPdf = OriginalPdf.local(source)).use { writer ->
            writer.append(List(31) { MistralOcrPageDto("Page ${it + 1}") })
            writer.append(
                listOf(
                    MistralOcrPageDto(
                        "![Figure](img-0.png)", listOf(
                            MistralOcrImageDto("img-0.png", "invalid-base64-unused", 0.0, 0.0, 100.0, 100.0)
                        ),
                        index = 0, dimensions = MistralOcrPageDimensionsDto(200.0, 100.0)
                    )
                )
            )
            writer.commit()
        }
        assertEquals(32, result.pages)
        assertTrue(result.warnings.isEmpty())
        assertTrue(result.imageFiles.single().endsWith("page-32-image-1.svg"))
        assertBlueSvg(result.imageFiles.single())
        assertTrue(Files.readString(output).contains("page-32-image-1.svg"))
    }

    @Test
    fun zaiUsesNormalizedBoundsAndTheirPageGroupWithoutDownloadingProviderImage() {
        val source = pdf(2)
        val output = directory.resolve("zai.md")
        val result = ZaiOcrClient.writeMarkdown(
            """{
            "md_results":"![Figure](https://cdn.example.com/figure.png)",
            "data_info":{"num_pages":2},
            "layout_details":[[],[{"index":0,"label":"image","content":"https://cdn.example.com/figure.png",
                "bbox_2d":[0.1,0.2,0.9,0.8],"width":200,"height":100}]]
        }""", output, true, originalPdf = OriginalPdf.local(source), download = { error("Unexpected image download") })
        assertTrue(result.warnings.isEmpty())
        assertTrue(result.imageFiles.single().endsWith("page-2-image-1.svg"))
        assertBlueSvg(result.imageFiles.single())
    }

    @Test
    fun documentIntelligenceUsesInchPolygonWithoutFigureDownload() {
        val source = pdf(2)
        val operation =
            "https://resource.cognitiveservices.azure.com/documentintelligence/documentModels/prebuilt-layout/" +
                    "analyzeResults/11111111-1111-1111-1111-111111111111?api-version=2024-11-30"
        var calls = 0
        val client = AzureDocumentIntelligenceClient(send = { request ->
            calls++
            if (request.method() == "POST") AzureDocumentIntelligenceResponse(
                202,
                mapOf("Operation-Location" to operation, "Retry-After" to "1"), byteArrayOf()
            )
            else {
                assertEquals(operation, request.uri().toString())
                AzureDocumentIntelligenceResponse(
                    200, emptyMap(), """{"status":"succeeded","analyzeResult":{
                    "content":"![Figure](figures/2.1)",
                    "pages":[{"pageNumber":1},{"pageNumber":2,"width":4,"height":2,"unit":"inch"}],
                    "figures":[{"id":"2.1","boundingRegions":[{"pageNumber":2,"polygon":[1,0.5,3,0.5,3,1.5,1,1.5]}]}]
                }}""".toByteArray()
                )
            }
        })
        val result = JsonSupport.json.decodeFromString<DocumentMarkdownWriteResult>(
            client.convertDocument(
                cli(),
                azureAccountConfig(null, "resource", null, null, null),
                localFile = source,
                outputFile = directory.resolve("di.md")
            )
        )
        assertEquals(2, calls)
        assertTrue(result.warnings.isEmpty())
        assertBlueSvg(result.imageFiles.single())
    }

    @Test
    fun cohereUsesOriginalPageWhenOnlyLaterPagesAreSelected() {
        val source = pdf(2)
        val client = AzureCohereParseClient(post = {
            AzureOcrResponse(
                200, """{"pages":[{"markdown":{"content":"![Figure](figure)","images":[{
                "id":"figure","bounding_box":{"top_left_x":40,"top_left_y":20,"bottom_right_x":360,"bottom_right_y":180}
            }]}}]}"""
            )
        })
        val result = JsonSupport.json.decodeFromString<MistralOcrWriteResult>(
            client.convertDocument(
                cli(),
                azureAccountConfig(null, "resource", null, null, null), "parse", localFile = source,
                outputFile = directory.resolve("cohere.md"), pageFrom = 2, pageTo = 2
            )
        )
        assertEquals(1, result.pages)
        assertTrue(result.warnings.isEmpty())
        assertTrue(result.imageFiles.single().endsWith("page-2-image-1.svg"))
        assertBlueSvg(result.imageFiles.single())
    }

    @Test
    fun validatesImageOptionsAndRewritesLargeInlineImages() {
        assertFailsWith<IllegalArgumentException> { DocumentImageOptions(dpi = 0) }
        assertFailsWith<IllegalArgumentException> { DocumentImageOptions(dpi = 601) }
        assertFailsWith<IllegalArgumentException> { DocumentImageOptions(paddingPoints = Double.NaN) }
        val data = "data:image/png;base64," + "A".repeat(100_000)
        assertEquals(
            "![Figure](images/figure.png)",
            rewriteMarkdownImageLinks("![Figure]($data)", mapOf(data to "images/figure.png"))
        )
    }

    private fun cli() = AzureCli(Path.of("/test/az"), run = { _, _, _, _ ->
        """{"accessToken":"fixture-token","expires_on":4102444800}"""
    })

    private fun pdf(pages: Int): Path {
        val path = directory.resolve("source-$pages.pdf")
        PDDocument().use { document ->
            repeat(pages) { index ->
                val page = PDPage(PDRectangle(200f, 100f))
                document.addPage(page)
                PDPageContentStream(document, page).use { content ->
                    content.setNonStrokingColor(if (index == pages - 1) Color.BLUE else Color.RED)
                    content.addRect(0f, 0f, 200f, 100f)
                    content.fill()
                }
            }
            document.save(path.toFile())
        }
        return path
    }

    private fun assertBlueSvg(value: String) {
        assertTrue(value.endsWith(".svg"))
        val xml = Files.readString(Path.of(value))
        assertTrue(!xml.contains("<!DOCTYPE"))
        val image = ByteArrayOutputStream().use { bytes ->
            PNGTranscoder().transcode(TranscoderInput(Path.of(value).toUri().toString()), TranscoderOutput(bytes))
            ImageIO.read(bytes.toByteArray().inputStream())
        }
        assertEquals(Color.BLUE.rgb, image.getRGB(image.width / 2, image.height / 2))
    }
}
