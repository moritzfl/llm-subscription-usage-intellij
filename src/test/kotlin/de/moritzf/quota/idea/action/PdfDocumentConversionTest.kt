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
    fun imageFallbackWarningsReachThePdfAction() {
        val output = Files.createTempFile("pdf-context-warning", ".md")
        try {
            val warnings = listOf("Page 3: SVG unavailable; used PNG at 300 DPI.")
            val response = JsonSupport.json.encodeToString(DocumentMarkdownWriteResult(output.toString(), warnings = warnings))
            assertEquals(warnings, checkConversionResult(response, output))
        } finally { Files.deleteIfExists(output) }
    }

    @Test
    fun relativeOutputNameResolvesBesideTheSelectedPdf() {
        val source = Path.of("project", "PDFs", "sample-report.pdf").toAbsolutePath()
        assertEquals(
            source.parent.resolve("sample-report.md"),
            resolveMarkdownOutput(source, "sample-report.md"),
        )
    }

    @Test
    fun absoluteOutputPathCanSelectAnotherDirectory() {
        val source = Path.of("project", "PDFs", "sample-report.pdf").toAbsolutePath()
        val chosen = Path.of("converted", "result.md").toAbsolutePath()
        assertEquals(chosen, resolveMarkdownOutput(source, chosen.toString()))
    }

    @Test
    fun relativeSourceAndOutputPathsAreResolvedAndNormalized() {
        val source = Path.of("project", "PDFs", "sample-report.pdf")
        assertEquals(
            Path.of("project", "converted", "result.md").toAbsolutePath(),
            resolveMarkdownOutput(source, "  ../converted/result.md  "),
        )
    }
}
