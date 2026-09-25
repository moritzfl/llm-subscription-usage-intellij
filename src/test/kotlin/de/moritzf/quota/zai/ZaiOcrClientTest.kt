package de.moritzf.quota.zai

import de.moritzf.quota.shared.DocumentLimits
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
}
