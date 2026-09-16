package de.moritzf.quota.openai.proxy.pdf

import java.awt.image.BufferedImage
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.util.LinkedHashMap
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer

/**
 * Crops a single figure region of a PDF page to PNG.
 *
 * Coordinates are normalized [0,1] with origin at the top-left of the rendered page. Imprecise
 * model-estimated boxes are clamped; degenerate boxes are skipped. Output is always the requested
 * rectangle, never a whole-page image.
 */
internal class PdfRegionImageExtractor private constructor(
    private val document: PDDocument,
) : Closeable {

    private val renderer = PDFRenderer(document)
    private val pageCache = object : LinkedHashMap<Int, BufferedImage>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, BufferedImage>?): Boolean {
            return size > MAX_CACHED_PAGES
        }
    }
    val pageCount: Int get() = document.numberOfPages

    fun renderRegion(region: PageRegion, targetFile: Path, dpi: Float = DEFAULT_DPI): Boolean {
        val pageIndex = region.page - 1
        if (pageIndex < 0 || pageIndex >= document.numberOfPages) return false
        val box = region.normalizedBox() ?: return false

        val pageImage = pageImage(pageIndex, dpi) ?: return false
        val pageWidthPx = pageImage.width
        val pageHeightPx = pageImage.height

        val x0 = floor(box.left * pageWidthPx).toInt().coerceIn(0, pageWidthPx - 1)
        val y0 = floor(box.top * pageHeightPx).toInt().coerceIn(0, pageHeightPx - 1)
        val x1 = ceil(box.right * pageWidthPx).toInt().coerceIn(x0 + 1, pageWidthPx)
        val y1 = ceil(box.bottom * pageHeightPx).toInt().coerceIn(y0 + 1, pageHeightPx)
        val width = x1 - x0
        val height = y1 - y0
        if (width < MIN_REGION_PX || height < MIN_REGION_PX) return false

        return runCatching {
            val crop = pageImage.getSubimage(x0, y0, width, height)
            val parent = targetFile.parent
            if (parent != null) Files.createDirectories(parent)
            ImageIO.write(crop, "png", targetFile.toFile())
        }.getOrDefault(false)
    }

    private fun pageImage(pageIndex: Int, dpi: Float): BufferedImage? {
        pageCache[pageIndex]?.let { return it }
        val image = runCatching {
            renderer.renderImageWithDPI(pageIndex, dpi, ImageType.RGB)
        }.getOrNull() ?: return null
        pageCache[pageIndex] = image
        return image
    }

    override fun close() {
        pageCache.clear()
        document.close()
    }

    data class PageRegion(
        val page: Int,
        val x0: Float,
        val y0: Float,
        val x1: Float,
        val y1: Float,
    ) {
        /** Returns the box with ordered corners clamped to [0,1], or null when degenerate. */
        fun normalizedBox(): Box? {
            val left = min(x0, x1).coerceIn(0f, 1f)
            val top = min(y0, y1).coerceIn(0f, 1f)
            val right = max(x0, x1).coerceIn(0f, 1f)
            val bottom = max(y0, y1).coerceIn(0f, 1f)
            if (right - left < MIN_NORMALIZED_SIZE || bottom - top < MIN_NORMALIZED_SIZE) return null
            return Box(left, top, right, bottom)
        }
    }

    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    companion object {
        private const val DEFAULT_DPI = 150f
        private const val MAX_CACHED_PAGES = 6
        private const val MIN_REGION_PX = 16
        private const val MIN_NORMALIZED_SIZE = 0.01f

        /**
         * Opens [pdfFile] for region extraction. Returns null when the file cannot be read as a PDF,
         * so callers keep the descriptive markdown without extracted image files.
         */
        fun open(pdfFile: Path): PdfRegionImageExtractor? {
            if (!Files.isRegularFile(pdfFile)) return null
            PdfImageIoPlugins.ensureRegistered()
            return runCatching { PdfRegionImageExtractor(Loader.loadPDF(pdfFile.toFile())) }.getOrNull()
        }
    }
}
