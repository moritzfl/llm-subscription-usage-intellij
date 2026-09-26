package de.moritzf.quota.openai.proxy.pdf

import de.moritzf.quota.shared.DocumentConversionProgress
import de.moritzf.quota.shared.DocumentModels
import de.moritzf.quota.shared.JsonSupport
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.io.TempDir

class PdfBoxMarkdownTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun extractsEmbeddedTextAsMarkdownAndWarnsAboutLimits() {
        val source = directory.resolve("note.pdf")
        writePdf(source, listOf("Hello PDFBox" to false, "BoldTitle" to true))
        val output = directory.resolve("note.md")

        val raw = PdfBoxMarkdown.convert(source, output, includeImages = true)
        val json = JsonSupport.json.parseToJsonElement(raw).jsonObject
        val warnings = json["warnings"]!!.jsonArray.map { it.jsonPrimitive.content }

        assertEquals(output.toString(), json["output_file"]!!.jsonPrimitive.content)
        assertEquals(1, json["page_count"]!!.jsonPrimitive.content.toInt())
        val markdown = Files.readString(output)
        assertTrue(markdown.contains("Hello PDFBox"), markdown)
        assertTrue(markdown.contains("**BoldTitle**"), markdown)
        assertTrue(warnings.contains(DocumentModels.PDFBOX_WARNING))
        assertTrue(warnings.contains(PdfBoxMarkdown.IMAGES_IGNORED))
        assertFalse(warnings.contains(PdfBoxMarkdown.NO_EMBEDDED_TEXT))
    }

    @Test
    fun honorsPageRangeAndLeavesOutputUnwrittenWhenCancelled() {
        val source = directory.resolve("pages.pdf")
        PDDocument().use { pdf ->
            repeat(2) { index ->
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
            pdf.save(source.toFile())
        }
        val output = directory.resolve("pages.md")
        val raw = PdfBoxMarkdown.convert(source, output, pageFrom = 2, pageTo = 2)
        val markdown = Files.readString(output)
        assertTrue(markdown.contains("Page 2."), markdown)
        assertFalse(markdown.contains("Page 1."), markdown)
        val json = JsonSupport.json.parseToJsonElement(raw).jsonObject
        assertEquals(2, json["page_from"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, json["page_to"]!!.jsonPrimitive.content.toInt())

        val cancelled = directory.resolve("cancelled.md")
        assertFailsWith<IllegalStateException> {
            PdfBoxMarkdown.convert(source, cancelled, progress = DocumentConversionProgress { completed, _, _ ->
                if (completed > 0) error("cancel")
            })
        }
        assertFalse(Files.exists(cancelled))
    }

    @Test
    fun rejectsNonPdfAndOutOfRangePages() {
        val text = directory.resolve("note.txt")
        Files.writeString(text, "not a pdf")
        assertFailsWith<IllegalArgumentException> { PdfBoxMarkdown.convert(text, directory.resolve("note.md")) }

        val source = directory.resolve("one.pdf")
        writePdf(source, listOf("Only" to false))
        val error = assertFailsWith<IllegalArgumentException> {
            PdfBoxMarkdown.convert(source, directory.resolve("one.md"), pageFrom = 2, pageTo = 2)
        }
        assertTrue(error.message.orEmpty().contains("1 pages"))
    }

    @Test
    fun warnsWhenAPageHasNoEmbeddedText() {
        val source = directory.resolve("blank.pdf")
        PDDocument().use { pdf ->
            pdf.addPage(PDPage())
            pdf.save(source.toFile())
        }
        val output = directory.resolve("blank.md")
        val raw = PdfBoxMarkdown.convert(source, output)
        val warnings = JsonSupport.json.parseToJsonElement(raw).jsonObject["warnings"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        assertTrue(warnings.contains(PdfBoxMarkdown.NO_EMBEDDED_TEXT))
        assertTrue(Files.isRegularFile(output))
    }

    private fun writePdf(path: Path, lines: List<Pair<String, Boolean>>) {
        PDDocument().use { pdf ->
            val page = PDPage()
            pdf.addPage(page)
            PDPageContentStream(pdf, page).use { content ->
                content.beginText()
                content.newLineAtOffset(40f, 700f)
                lines.forEach { (text, bold) ->
                    val font = if (bold) Standard14Fonts.FontName.HELVETICA_BOLD else Standard14Fonts.FontName.HELVETICA
                    content.setFont(PDType1Font(font), 12f)
                    content.showText(text)
                    content.newLineAtOffset(0f, -16f)
                }
                content.endText()
            }
            pdf.save(path.toFile())
        }
    }
}
