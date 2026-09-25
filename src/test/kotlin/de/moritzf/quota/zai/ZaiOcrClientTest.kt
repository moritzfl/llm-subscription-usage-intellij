package de.moritzf.quota.zai

import de.moritzf.quota.shared.DocumentLimits
import de.moritzf.quota.shared.JsonSupport
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.net.http.HttpRequest
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.text.PDFTextStripper

class ZaiOcrClientTest {
    @Test
    fun resolveFileKeepsPublicUrlAndWrapsLocalPdf() {
        assertEquals("https://example.com/a.pdf", ZaiOcrClient.resolveFile("https://example.com/a.pdf", null))
        val dir = Files.createTempDirectory("zai-ocr")
        val pdf = dir.resolve("doc.pdf")
        Files.write(pdf, "%PDF-1.4".toByteArray())
        val encoded = ZaiOcrClient.resolveFile(null, pdf)
        assertTrue(encoded.startsWith("data:application/pdf;base64,"))
    }

    @Test
    fun resolveFileRejectsOversizedLocalDocument() {
        val dir = Files.createTempDirectory("zai-ocr-big")
        val pdf = dir.resolve("big.pdf")
        RandomAccessFile(pdf.toFile(), "rw").use { it.setLength(DocumentLimits.MAX_INLINE_BYTES + 1) }
        val exception = assertFailsWith<ZaiQuotaException> {
            ZaiOcrClient.resolveFile(null, pdf)
        }
        assertTrue(exception.message!!.contains("too large"))
    }

    @Test
    fun writeMarkdownUsesMdResults() {
        val dir = Files.createTempDirectory("zai-ocr-md")
        val out = dir.resolve("doc.md")
        val result = ZaiOcrClient.writeMarkdown(
            """{"md_results":"# Hello","data_info":{"num_pages":2}}""",
            out,
        )
        assertEquals(out.toString(), result.outputFile)
        assertEquals(2, result.pages)
        assertEquals("# Hello", Files.readString(out))
        assertEquals(emptyList(), result.imageFiles)
    }

    @Test
    fun writeMarkdownPersistsCropImagesAndRewritesMarkdown() {
        val dir = Files.createTempDirectory("zai-ocr-img")
        val out = dir.resolve("doc.md")
        val png = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
        val url = "https://cdn.example.com/crops/figure-1.png"
        val body = """
            {
              "md_results": "See ![]($url) and a data image",
              "layout_details": [
                [
                  {"index": 1, "label": "text", "content": "See"},
                  {"index": 2, "label": "image", "content": "$url"},
                  {"index": 3, "label": "image", "content": "data:image/png;base64,$png"}
                ]
              ],
              "data_info": {"num_pages": 1}
            }
        """.trimIndent()

        val result = ZaiOcrClient.writeMarkdown(body, out, includeImages = true) { requested ->
            assertEquals(url, requested)
            byteArrayOf(9, 8, 7)
        }

        assertEquals(2, result.imageFiles.size)
        val first = Path.of(result.imageFiles[0])
        val second = Path.of(result.imageFiles[1])
        assertEquals(dir, first.parent.parent)
        assertEquals(byteArrayOf(9, 8, 7).toList(), Files.readAllBytes(first).toList())
        assertEquals(byteArrayOf(1, 2, 3, 4).toList(), Files.readAllBytes(second).toList())
        assertEquals("See ![](${first.parent.fileName}/${first.fileName}) and a data image", Files.readString(out))
        assertEquals(2, result.warnings.size)
    }

    @Test
    fun writeMarkdownOffsetsFigurePagesForALaterSlice() {
        val dir = Files.createTempDirectory("zai-ocr-offset")
        val out = dir.resolve("doc.md")
        val png = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        val body = """
            {"md_results":"![Figure](data:image/png;base64,$png)","layout_details":[[{
              "label":"image","content":"data:image/png;base64,$png","bbox_2d":[0.1,0.1,0.2,0.2]
            }]]}
        """.trimIndent()
        val result = ZaiOcrClient.writeMarkdown(body, out, includeImages = true, pageOffset = 30)
        assertTrue(result.imageFiles.single().contains("page-31-image-1"))
    }

    @Test
    fun splitsLocalPdfAtThirtyPagesAndJoinsMarkdown() {
        val dir = Files.createTempDirectory("zai-ocr-chunks")
        val source = dir.resolve("source.pdf")
        val output = dir.resolve("result.md")
        createPdf(source, 31)
        val sizes = mutableListOf<Int>()
        var nextPage = 1
        val client = ZaiOcrClient(post = { request ->
            val file = JsonSupport.json.parseToJsonElement(requestBody(request))
                .let { (it as kotlinx.serialization.json.JsonObject)["file"] }
                .toString().trim('"')
            val bytes = Base64.getDecoder().decode(file.substringAfter("base64,"))
            val count = Loader.loadPDF(bytes).use { pdf ->
                val text = PDFTextStripper().getText(pdf)
                (1..pdf.numberOfPages).forEach { index ->
                    val number = nextPage++
                    assertTrue(text.contains("Page $number."), "missing page $number")
                }
                pdf.numberOfPages
            }
            sizes += count
            val from = nextPage - count
            ZaiHttpResult(200, chunkBody(from, from + count - 1))
        })
        val result = JsonSupport.json.decodeFromString<ZaiOcrWriteResult>(
            client.convertDocument("token", localFile = source, outputFile = output, includeImages = false),
        )
        assertEquals(listOf(30, 1), sizes)
        assertEquals(31, result.pages)
        val headings = Regex("(?m)^# Page (\\d+)$").findAll(Files.readString(output)).map { it.groupValues[1].toInt() }.toList()
        assertEquals((1..31).toList(), headings)
    }

    @Test
    fun smallPdfStaysOneRequestAndLongPdfWritesJoinedMarkdownBesideTheSource() {
        val dir = Files.createTempDirectory("zai-ocr-raw")
        val small = dir.resolve("small.pdf")
        val large = dir.resolve("large.pdf")
        createPdf(small, 2)
        createPdf(large, 31)
        var calls = 0
        val client = ZaiOcrClient(post = { request ->
            calls++
            val file = requestBody(request)
            val bytes = Base64.getDecoder().decode(file.substringAfter("base64,").substringBefore('"'))
            val count = Loader.loadPDF(bytes).use { it.numberOfPages }
            ZaiHttpResult(200, chunkBody(1, count))
        })
        val single = JsonSupport.json.decodeFromString<ZaiOcrWriteResult>(
            client.convertDocument("token", localFile = small, includeImages = false),
        )
        assertEquals(1, calls)
        assertEquals(2, single.pages)
        assertEquals(small.resolveSibling("small.md").toString(), single.outputFile)
        calls = 0
        val joined = JsonSupport.json.decodeFromString<ZaiOcrWriteResult>(
            client.convertDocument("token", localFile = large, includeImages = false),
        )
        assertEquals(2, calls)
        assertEquals(31, joined.pages)
        assertTrue(Files.readString(large.resolveSibling("large.md")).contains("# Page 1"))
    }

    @Test
    fun cancellationAfterChunkPreventsNextRequestAndPublishing() {
        val dir = Files.createTempDirectory("zai-ocr-cancel")
        val source = dir.resolve("source.pdf")
        val output = dir.resolve("result.md")
        createPdf(source, 31)
        var calls = 0
        val client = ZaiOcrClient(post = {
            calls++
            ZaiHttpResult(200, chunkBody(1, 30))
        })
        assertFailsWith<CancellationException> {
            client.convertDocument("token", localFile = source, outputFile = output, includeImages = false,
                progress = { completed, _, _ -> if (completed == 30) throw CancellationException() })
        }
        assertEquals(1, calls)
        assertFalse(Files.exists(output))
    }

    @Test
    fun failedLaterChunkPreservesExistingOutput() {
        val dir = Files.createTempDirectory("zai-ocr-fail")
        val source = dir.resolve("source.pdf")
        val output = dir.resolve("result.md")
        createPdf(source, 31)
        Files.writeString(output, "Existing document")
        var calls = 0
        val client = ZaiOcrClient(post = {
            if (++calls == 1) ZaiHttpResult(200, chunkBody(1, 30)) else ZaiHttpResult(500, """{"message":"nope"}""")
        })
        val error = assertFailsWith<ZaiQuotaException> {
            client.convertDocument("token", localFile = source, outputFile = output, includeImages = false)
        }
        assertTrue(error.message.orEmpty().contains("31–31"))
        assertEquals(2, calls)
        assertEquals("Existing document", Files.readString(output))
    }

    private fun chunkBody(from: Int, to: Int): String {
        val markdown = (from..to).joinToString("\\n\\n") { "# Page $it" }
        return """{"md_results":"$markdown","data_info":{"num_pages":${to - from + 1}}}"""
    }

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
        request.bodyPublisher().orElseThrow().subscribe(object : Flow.Subscriber<ByteBuffer> {
            override fun onSubscribe(subscription: Flow.Subscription) = subscription.request(Long.MAX_VALUE)
            override fun onNext(item: ByteBuffer) {
                val bytes = ByteArray(item.remaining())
                item.get(bytes)
                output.write(bytes)
            }
            override fun onError(throwable: Throwable) = completed.countDown()
            override fun onComplete() = completed.countDown()
        })
        assertTrue(completed.await(5, TimeUnit.SECONDS))
        return output.toString(StandardCharsets.UTF_8)
    }
}
