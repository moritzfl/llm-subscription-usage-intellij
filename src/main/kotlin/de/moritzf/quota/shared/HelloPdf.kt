package de.moritzf.quota.shared

import java.awt.image.BufferedImage
import java.nio.file.Path
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font

/** One-page sample used by the settings document test. Created on the fly, never checked in. */
internal object HelloPdf {
    const val TEXT = "Hello from LLM Subscription Usage"
    private const val LIBERATION = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf"

    fun write(path: Path) {
        val media = PDRectangle(PDRectangle.LETTER.height, PDRectangle.LETTER.width)
        PDDocument().use { document ->
            val page = PDPage(media)
            document.addPage(page)
            val font = liberation(document)
            val margin = 36f
            val fontSize = ((media.width - margin * 2) / (font.getStringWidth(TEXT) / 1000f)).coerceAtMost(48f)
            val textWidth = font.getStringWidth(TEXT) / 1000f * fontSize
            val lineHeight = fontSize * 1.35f
            val lines = ((media.height - margin * 2) / lineHeight).toInt().coerceAtLeast(1)
            var y = (media.height + lines * lineHeight) / 2f - fontSize
            val x = (media.width - textWidth) / 2f
            PDPageContentStream(document, page).use { content ->
                content.setFont(font, fontSize)
                repeat(lines) {
                    content.beginText()
                    content.newLineAtOffset(x, y)
                    content.showText(TEXT)
                    content.endText()
                    y -= lineHeight
                }
            }
            document.save(path.toFile())
        }
    }

    private fun liberation(document: PDDocument): PDType0Font {
        val stream = PDType0Font::class.java.getResourceAsStream(LIBERATION)
            ?: error("PDFBox Liberation Sans is missing")
        return stream.use { PDType0Font.load(document, it, true) }
    }

    fun renderPage(path: Path, dpi: Float = 110f): BufferedImage {
        return Loader.loadPDF(path.toFile()).use { PDFRenderer(it).renderImageWithDPI(0, dpi) }
    }
}
