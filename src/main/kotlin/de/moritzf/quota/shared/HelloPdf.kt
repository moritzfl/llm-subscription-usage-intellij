package de.moritzf.quota.shared

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import org.apache.batik.transcoder.SVGAbstractTranscoder
import org.apache.batik.transcoder.TranscoderInput
import org.apache.batik.transcoder.TranscoderOutput
import org.apache.batik.transcoder.image.PNGTranscoder
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.rendering.PDFRenderer

/** One-page sample used by the settings document test. Created on the fly, never checked in. */
internal object HelloPdf {
    const val TEXT = "Hello from LLM Subscription Usage"
    internal val TABLE = arrayOf(
        arrayOf("Provider", "Window", "Used"),
        arrayOf("OpenAI", "5 hours", "12%"),
        arrayOf("Mistral", "Week", "40%"),
    )
    private const val LIBERATION = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf"
    private const val ICON = "/META-INF/pluginIcon.svg"
    private const val PAGE_WIDTH = 720f
    private const val MARGIN = 28f
    private const val LOGO_SIZE = 176f
    private const val TITLE_SIZE = 22f
    private const val TABLE_FONT = 18f
    private const val ROW_HEIGHT = 36f
    private const val GAP = 12f

    fun write(path: Path) {
        val tableHeight = ROW_HEIGHT * TABLE.size
        val media = PDRectangle(PAGE_WIDTH, MARGIN + LOGO_SIZE + GAP + TITLE_SIZE + GAP + tableHeight + MARGIN)
        PDDocument().use { document ->
            val page = PDPage(media)
            document.addPage(page)
            val font = liberation(document)
            val logo = logo(document)
            val titleWidth = font.getStringWidth(TEXT) / 1000f * TITLE_SIZE
            val tableWidth = (PAGE_WIDTH - MARGIN * 2).coerceAtMost(520f)
            val blockWidth = maxOf(LOGO_SIZE, titleWidth, tableWidth)
            val left = (media.width - blockWidth) / 2f
            var top = media.height - MARGIN
            PDPageContentStream(document, page).use { content ->
                content.drawImage(logo, left + (blockWidth - LOGO_SIZE) / 2f, top - LOGO_SIZE, LOGO_SIZE, LOGO_SIZE)
                top -= LOGO_SIZE + GAP
                content.beginText()
                content.setFont(font, TITLE_SIZE)
                content.newLineAtOffset(left + (blockWidth - titleWidth) / 2f, top - TITLE_SIZE)
                content.showText(TEXT)
                content.endText()
                top -= TITLE_SIZE + GAP
                drawTable(content, font, left + (blockWidth - tableWidth) / 2f, top - tableHeight, tableWidth)
            }
            document.save(path.toFile())
        }
    }

    private fun liberation(document: PDDocument): PDType0Font {
        val stream = PDType0Font::class.java.getResourceAsStream(LIBERATION)
            ?: error("PDFBox Liberation Sans is missing")
        return stream.use { PDType0Font.load(document, it, true) }
    }

    private fun logo(document: PDDocument): PDImageXObject {
        val raw = HelloPdf::class.java.getResourceAsStream(ICON) ?: error("Plugin icon is missing")
        val xml = String(raw.use { it.readBytes() }, StandardCharsets.UTF_8)
            .replace(Regex("""<!DOCTYPE[^>]*>"""), "")
            .replace("""width="100%" height="100%"""", """width="1024" height="1024"""")
        val png = ByteArrayOutputStream()
        val transcoder = PNGTranscoder()
        transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_WIDTH, 1024f)
        transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_HEIGHT, 1024f)
        transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_ALLOW_EXTERNAL_RESOURCES, false)
        transcoder.transcode(
            TranscoderInput(xml.byteInputStream(StandardCharsets.UTF_8)),
            TranscoderOutput(png),
        )
        return PDImageXObject.createFromByteArray(document, png.toByteArray(), "logo")
    }

    private fun drawTable(content: PDPageContentStream, font: PDType0Font, x: Float, bottom: Float, width: Float) {
        val rows = TABLE.size
        val cols = TABLE[0].size
        val height = ROW_HEIGHT * rows
        val colWidth = width / cols
        content.setNonStrokingColor(0.94f, 0.94f, 0.95f)
        content.addRect(x, bottom + height - ROW_HEIGHT, width, ROW_HEIGHT)
        content.fill()
        content.setStrokingColor(108 / 255f, 112 / 255f, 126 / 255f)
        content.setLineWidth(1.75f)
        for (row in 0..rows) {
            val y = bottom + row * ROW_HEIGHT
            content.moveTo(x, y)
            content.lineTo(x + width, y)
        }
        for (col in 0..cols) {
            val lineX = x + col * colWidth
            content.moveTo(lineX, bottom)
            content.lineTo(lineX, bottom + height)
        }
        content.stroke()
        content.setNonStrokingColor(0f, 0f, 0f)
        content.setFont(font, TABLE_FONT)
        val pad = 12f
        TABLE.forEachIndexed { rowIndex, cells ->
            val cellBottom = bottom + height - (rowIndex + 1) * ROW_HEIGHT
            val baseline = cellBottom + (ROW_HEIGHT - TABLE_FONT) / 2f + TABLE_FONT * 0.2f
            cells.forEachIndexed { colIndex, cell ->
                content.beginText()
                content.newLineAtOffset(x + colIndex * colWidth + pad, baseline)
                content.showText(cell)
                content.endText()
            }
        }
    }

    fun renderPage(path: Path, dpi: Float = 110f): BufferedImage {
        return Loader.loadPDF(path.toFile()).use { PDFRenderer(it).renderImageWithDPI(0, dpi) }
    }
}
