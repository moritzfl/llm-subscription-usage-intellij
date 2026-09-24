package de.moritzf.quota.idea.action

import de.moritzf.quota.shared.DocumentMarkdownWriteResult
import de.moritzf.quota.shared.JsonSupport
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PdfDocumentConversionTest {
    @Test
    fun acceptsOnlyTheRequestedMarkdownOutput() {
        val output = Files.createTempFile("pdf-context-action", ".md")
        try {
            Files.writeString(output, "# Document")
            val response = JsonSupport.json.encodeToString(DocumentMarkdownWriteResult(output.toString()))
            checkConversionResult(response, output)
            assertEquals("# Document", Files.readString(output))

            val wrongFile = JsonSupport.json.encodeToString(
                DocumentMarkdownWriteResult(output.resolveSibling("elsewhere.md").toString()),
            )
            assertFailsWith<IllegalStateException> { checkConversionResult(wrongFile, output) }
        } finally {
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun providerErrorsCannotLookSuccessfulWhenAnOlderOutputFileExists() {
        val output = Files.createTempFile("pdf-context-action-old", ".md")
        try {
            val failure = assertFailsWith<IllegalStateException> {
                checkConversionResult("""{"error":"OCR unavailable"}""", output)
            }
            assertEquals("OCR unavailable", failure.message)
        } finally {
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun relativeOutputNameResolvesBesideTheSelectedPdf() {
        val source = Path.of("C:/project/PDFs/sample-report.pdf")
        assertEquals(
            source.parent.resolve("sample-report.md"),
            resolveMarkdownOutput(source, "sample-report.md"),
        )
        val chosen = source.parent.resolve("converted/result.md")
        assertEquals(chosen, resolveMarkdownOutput(source, chosen.toString()))
    }
}
