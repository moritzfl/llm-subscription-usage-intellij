package de.moritzf.quota.mistral

import de.moritzf.quota.shared.JsonSupport
import java.io.ByteArrayOutputStream
import java.net.http.HttpRequest
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.io.TempDir

class MistralOcrClientTest {
    @TempDir lateinit var directory: Path

    @Test
    fun longPdfIsSentInlineInSlicesWithoutUploads() {
        val source = directory.resolve("source.pdf")
        createPdf(source, 61)
        val requests = mutableListOf<String>()
        val client = MistralOcrClient(post = { request -> answer(request, requests) })

        val result =
            JsonSupport.json.decodeFromString<MistralOcrWriteResult>(
                client.convertDocument("key", localFile = source, includeImages = false)
            )

        assertEquals(
            listOf((1..30), (31..60), (61..61)).map { it.joinToString(",") },
            requests,
        )
        assertEquals(61, result.pages)
        assertEquals(directory.resolve("source.md").toString(), result.outputFile)
        assertEquals((1..61).map { "# Page $it" }, headings())
    }

    @Test
    fun timedOutPageIsIsolatedAndSentAsImage() {
        val source = directory.resolve("source.pdf")
        createPdf(source, 31)
        val requests = mutableListOf<String>()
        val client =
            MistralOcrClient(
                post = { request ->
                    val numbers = pdfPages(request)
                    if (numbers != null && 5 in numbers) {
                        requests += numbers.joinToString(",")
                        throw HttpTimeoutException("request timed out")
                    }
                    answer(request, requests)
                }
            )

        client.convertDocument("key", localFile = source, includeImages = false)

        assertEquals(
            listOf((1..30).joinToString(",")) +
                (1..5).map(Int::toString) +
                "image" +
                (6..31).map(Int::toString),
            requests,
        )
        assertEquals(
            (1..4).map { "# Page $it" } + "# Image page" + (6..31).map { "# Page $it" },
            headings(),
        )
    }

    @Test
    fun clientErrorStopsWithoutPageFallback() {
        val source = directory.resolve("source.pdf")
        createPdf(source, 3)
        Files.writeString(directory.resolve("source.md"), "Existing document")
        var calls = 0
        val client =
            MistralOcrClient(
                post = {
                    calls++
                    MistralHttpResult(400, """{"message":"too many pages"}""")
                }
            )

        val error =
            assertFailsWith<MistralQuotaException> {
                client.convertDocument("key", localFile = source, includeImages = false)
            }
        assertEquals("Mistral OCR failed for pages 1–3 (HTTP 400): too many pages", error.message)
        assertEquals(1, calls)
        assertEquals("Existing document", Files.readString(directory.resolve("source.md")))
    }

    private fun answer(request: HttpRequest, requests: MutableList<String>): MistralHttpResult {
        assertTrue(request.uri().path.endsWith("/v1/ocr"), "Local PDFs must not be uploaded")
        val document = payload(request).document
        val pages =
            document.imageUrl?.let { image ->
                assertTrue(image.startsWith("data:image/png;base64,"))
                val png = Base64.getDecoder().decode(image.substringAfter("base64,"))
                assertTrue(ImageIO.read(png.inputStream()).width > 0)
                requests += "image"
                listOf(MistralOcrPageDto("# Image page"))
            }
                ?: checkNotNull(pdfPages(request))
                    .also { requests += it.joinToString(",") }
                    .map { MistralOcrPageDto("# Page $it") }
        return MistralHttpResult(200, JsonSupport.json.encodeToString(MistralOcrResponseDto(pages)))
    }

    private fun pdfPages(request: HttpRequest): List<Int>? {
        val url = payload(request).document.documentUrl ?: return null
        assertTrue(url.startsWith("data:application/pdf;base64,"))
        val bytes = Base64.getDecoder().decode(url.substringAfter("base64,"))
        return Loader.loadPDF(bytes).use { pdf ->
            Regex("Page (\\d+)\\.")
                .findAll(PDFTextStripper().getText(pdf))
                .map { it.groupValues[1].toInt() }
                .toList()
        }
    }

    private fun payload(request: HttpRequest) =
        JsonSupport.json.decodeFromString<MistralOcrRequestDto>(requestBody(request))

    private fun headings() =
        Regex("(?m)^# .+$")
            .findAll(Files.readString(directory.resolve("source.md")))
            .map { it.value }
            .toList()

    private fun createPdf(path: Path, pages: Int) {
        PDDocument().use { pdf ->
            repeat(pages) { index ->
                val page = PDPage()
                pdf.addPage(page)
                PDPageContentStream(pdf, page).use { content ->
                    content.beginText()
                    content.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                    content.newLineAtOffset(40f, 700f)
                    content.showText("Page ${index + 1}.")
                    content.endText()
                }
            }
            pdf.save(path.toFile())
        }
    }

    private fun requestBody(request: HttpRequest): String {
        val output = ByteArrayOutputStream()
        val completed = CountDownLatch(1)
        request
            .bodyPublisher()
            .orElseThrow()
            .subscribe(
                object : Flow.Subscriber<ByteBuffer> {
                    override fun onSubscribe(subscription: Flow.Subscription) =
                        subscription.request(Long.MAX_VALUE)

                    override fun onNext(item: ByteBuffer) {
                        val bytes = ByteArray(item.remaining())
                        item.get(bytes)
                        output.write(bytes)
                    }

                    override fun onError(throwable: Throwable) = completed.countDown()

                    override fun onComplete() = completed.countDown()
                }
            )
        assertTrue(completed.await(5, TimeUnit.SECONDS))
        return output.toString(Charsets.UTF_8)
    }
}
