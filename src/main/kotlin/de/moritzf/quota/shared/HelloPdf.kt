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
    private const val LIBERATION = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf"
    private const val ICON = "/META-INF/pluginIcon.svg"
    private const val PAGE_WIDTH = 792f
    private const val LOGO_SIZE = 300f
    private const val GAP = 28f
    private const val MARGIN = 40f

    fun write(path: Path) {
        PDDocument().use { document ->
            val font = liberation(document)
            val fontSize = ((PAGE_WIDTH - MARGIN * 2) / (font.getStringWidth(TEXT) / 1000f)).coerceAtMost(36f)
            val textBlock = fontSize * 1.35f
            val media = PDRectangle(PAGE_WIDTH, MARGIN + LOGO_SIZE + GAP + textBlock + MARGIN)
            val page = PDPage(media)
            document.addPage(page)
            val logo = logo(document)
            val logoX = (media.width - LOGO_SIZE) / 2f
            val logoY = MARGIN + textBlock + GAP
            val textWidth = font.getStringWidth(TEXT) / 1000f * fontSize
            PDPageContentStream(document, page).use { content ->
                content.drawImage(logo, logoX, logoY, LOGO_SIZE, LOGO_SIZE)
                content.beginText()
                content.setFont(font, fontSize)
                content.newLineAtOffset((media.width - textWidth) / 2f, MARGIN + fontSize * 0.25f)
                content.showText(TEXT)
                content.endText()
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

    fun renderPage(path: Path, dpi: Float = 110f): BufferedImage {
        return Loader.loadPDF(path.toFile()).use { PDFRenderer(it).renderImageWithDPI(0, dpi) }
    }
}
