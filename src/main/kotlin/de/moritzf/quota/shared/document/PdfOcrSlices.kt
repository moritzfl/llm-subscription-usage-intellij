package de.moritzf.quota.shared.document

import de.moritzf.quota.shared.DocumentConversionProgress
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.sqrt
import org.apache.pdfbox.multipdf.PageExtractor
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer

/**
 * Sends a PDF to an OCR API in page slices within its request limits. OCR services fail a whole
 * request for a single page they cannot process, so a slice that fails server-side is retried page
 * by page, and a page that still fails is sent once more as a rendered PNG.
 */
internal class PdfOcrSlices(
    private val service: String,
    private val maxPages: Int,
    private val maxBytes: Long,
    private val maxImageBytes: Long = maxBytes,
    private val fail: (String) -> Exception,
) {
    fun interface Sender {
        /**
         * Converts pages [from]..[to] from [bytes]. Returns false after a server-side failure (HTTP
         * 5xx or timeout) unless [lastAttempt], in which case every failure must throw.
         */
        fun send(bytes: ByteArray, mime: String, from: Int, to: Int, lastAttempt: Boolean): Boolean
    }

    /** [original] is sent unchanged when the whole document fits into one request. */
    fun convert(
        pdf: PDDocument,
        progress: DocumentConversionProgress,
        original: ByteArray? = null,
        sender: Sender,
    ) {
        val total = pdf.numberOfPages
        if (total < 1) throw fail("PDF has no pages.")

        fun convertSlice(bytes: ByteArray, from: Int, to: Int) {
            if (sender.send(bytes, PDF, from, to, lastAttempt = false)) return
            if (from == to) sender.send(renderPng(pdf, from), PNG, from, to, lastAttempt = true)
            else for (page in from..to) convertSlice(extract(pdf, page, page), page, page)
        }

        var from = 1
        while (from <= total) {
            var to = minOf(from + maxPages - 1, total)
            progress.update(from - 1, total, "Preparing pages $from–$to of $total")
            var bytes =
                original?.takeIf { from == 1 && to == total && it.size <= maxBytes }
                    ?: extract(pdf, from, to)
            while (bytes.size > maxBytes) {
                if (to == from)
                    throw fail(
                        "PDF page $from exceeds $service's ${megabytes(maxBytes)} request limit."
                    )
                to = from + (to - from) / 2
                progress.update(from - 1, total, "Preparing pages $from–$to of $total")
                bytes = extract(pdf, from, to)
            }
            convertSlice(bytes, from, to)
            from = to + 1
        }
    }

    private fun megabytes(bytes: Long) = "${bytes / (1024 * 1024)} MB"

    private fun extract(pdf: PDDocument, from: Int, to: Int): ByteArray =
        PageExtractor(pdf, from, to).extract().use { slice ->
            ByteArrayOutputStream().use { stream ->
                slice.save(stream)
                stream.toByteArray()
            }
        }

    private fun renderPng(pdf: PDDocument, page: Int): ByteArray {
        PdfImageIoPlugins.ensureRegistered()
        val box = pdf.getPage(page - 1).cropBox
        val dpi = minOf(IMAGE_DPI, 72f * sqrt(MAX_IMAGE_PIXELS / (box.width * box.height)))
        val image = PDFRenderer(pdf).renderImageWithDPI(page - 1, dpi, ImageType.RGB)
        val bytes =
            ByteArrayOutputStream().use { stream ->
                if (!ImageIO.write(image, "png", stream))
                    throw fail("Could not encode PDF page $page as PNG.")
                stream.toByteArray()
            }
        if (bytes.size > maxImageBytes)
            throw fail("PDF page $page exceeds $service's ${megabytes(maxImageBytes)} image limit.")
        return bytes
    }

    companion object {
        const val PDF = "application/pdf"
        const val PNG = "image/png"
        private const val IMAGE_DPI = 200f
        private const val MAX_IMAGE_PIXELS = 25_000_000f
    }
}
