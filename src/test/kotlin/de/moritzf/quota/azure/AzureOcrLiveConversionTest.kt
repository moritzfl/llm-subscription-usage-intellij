package de.moritzf.quota.azure

import de.moritzf.quota.mistral.MistralOcrWriteResult
import de.moritzf.quota.shared.JsonSupport
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
        val (pageCount, sourceText) = Loader.loadPDF(source.toFile()).use { it.numberOfPages to PDFTextStripper().getText(it) }
        assertTrue(pageCount > 30, "Use a long PDF to exercise multiple real requests")
        val completed = mutableListOf<Int>()
        val started = System.nanoTime()
        val result = JsonSupport.json.decodeFromString<MistralOcrWriteResult>(AzureOcrClient().convertDocument(
            AzureCli(assertNotNull(AzureCli.findExecutable())),
            azureAccountConfig(System.getenv("AZURE_OCR_LIVE_SUBSCRIPTION"), resource, null, null, null),
            model, localFile = source, outputFile = output,
            progress = { done, total, detail ->
                if (total > 0 && completed.lastOrNull() != done) {
                    completed += done
                    println("Azure OCR: $done/$total — $detail")
                }
            },
        ))
        assertEquals(pageCount, result.pages)
        assertEquals(pageCount, completed.last())
        assertEquals(completed.sorted(), completed)
        assertEquals(result.imageFiles.size, result.imageFiles.distinct().size)
        val markdown = Files.readString(output)
        assertTrue(markdown.isNotBlank())
        result.imageFiles.forEach { value ->
            val image = Path.of(value)
            assertTrue(Files.size(image) > 0)
            assertNotNull(ImageIO.read(image.toFile()), "Unreadable image: $image")
            val link = URI(null, null, "${image.parent.fileName}/${image.fileName}", null).toASCIIString()
                .replace("(", "%28").replace(")", "%29")
            assertTrue(markdown.contains(link), "Image not linked: $image")
        }
        val sourceWords = words(sourceText)
        val overlap = words(markdown).intersect(sourceWords).size.toDouble() / sourceWords.size.coerceAtLeast(1)
        assertTrue(overlap >= 0.80, "Only $overlap of source vocabulary retained")
        println("Azure OCR live passed: pages=$pageCount, images=${result.imageFiles.size}, " +
            "source-word coverage=$overlap, seconds=${(System.nanoTime() - started) / 1_000_000_000}, output=$output")
    }

    private fun words(text: String): Set<String> = Regex("[\\p{L}\\p{N}]{4,}").findAll(text.lowercase())
        .map { it.value }.toSet()
}
