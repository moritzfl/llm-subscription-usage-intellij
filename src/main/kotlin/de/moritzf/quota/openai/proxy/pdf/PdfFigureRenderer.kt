package de.moritzf.quota.openai.proxy.pdf

import java.awt.Color
import java.awt.Dimension
import java.awt.geom.Rectangle2D
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.math.ceil
import kotlin.math.abs
import kotlin.math.floor
import org.apache.batik.dom.GenericDOMImplementation
import org.apache.batik.svggen.SVGGeneratorContext
import org.apache.batik.svggen.SVGGraphics2D
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.graphics.blend.BlendMode
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.rendering.RenderDestination

/** A provider's top-left normalized coordinates on the displayed (rotated) PDF page. */
internal data class PdfFigureRegion(
    val page: Int,
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
    val referenceWidth: Double? = null,
    val referenceHeight: Double? = null,
) {
    companion object {
        fun fromPixels(page: Int, box: List<Double>, width: Double?, height: Double?): PdfFigureRegion? {
            if (box.size != 4 || width == null || height == null || !width.isFinite() || !height.isFinite() ||
                width <= 0 || height <= 0
            ) return null
            return PdfFigureRegion(
                page,
                box[0] / width,
                box[1] / height,
                box[2] / width,
                box[3] / height,
                width,
                height
            )
        }
    }
}

/** Renders only the requested viewport; PDFBox supplies CropBox and rotation transforms. */
internal class PdfFigureRenderer(private val document: PDDocument) : AutoCloseable {
    private val renderer = PDFRenderer(document)

    init {
        PdfImageIoPlugins.ensureRegistered()
    }

    fun renderSvg(region: PdfFigureRegion, target: Path, paddingPoints: Double) {
        val crop = crop(region, paddingPoints)
        val page = document.getPage(region.page - 1)
        if (page.cosObject.containsKey(COSName.GROUP) || hasComplexTransparency(page.resources, mutableSetOf())) {
            throw IOException("PDF transparency requires raster rendering")
        }
        val dom = GenericDOMImplementation.getDOMImplementation()
            .createDocument("http://www.w3.org/2000/svg", "svg", null)
        val context = SVGGeneratorContext.createDefault(dom).apply {
            comment = "PDF figure exported from the original document; text is stored as outlines."
            extensionHandler = PdfSvgPaintHandler()
        }
        val graphics = SVGGraphics2D(context, true)
        try {
            graphics.background = Color.WHITE
            graphics.svgCanvasSize = Dimension(ceil(crop.width).toInt(), ceil(crop.height).toInt())
            graphics.clip(Rectangle2D.Double(0.0, 0.0, crop.width, crop.height))
            graphics.translate(-crop.x, -crop.y)
            renderer.renderPageToGraphics(region.page - 1, graphics, 1f, 1f, RenderDestination.VIEW)
            val root = graphics.root
            root.setAttribute("viewBox", "0 0 ${crop.width} ${crop.height}")
            root.setAttribute("width", crop.width.toString())
            root.setAttribute("height", crop.height.toString())
            root.setAttribute("overflow", "hidden")
            // Serialize the DOM without Batik's external SVG 1.0 DOCTYPE.
            val transformer = TransformerFactory.newDefaultInstance().newTransformer().apply {
                setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            }
            Files.newBufferedWriter(target).use { transformer.transform(DOMSource(root), StreamResult(it)) }
        } finally {
            graphics.dispose()
        }
    }

    fun renderPng(region: PdfFigureRegion, target: Path, dpi: Int, paddingPoints: Double) {
        val crop = crop(region, paddingPoints)
        val scale = dpi / 72.0
        val page = document.getPage(region.page - 1)
        if (ceil(page.cropBox.width * scale) * ceil(page.cropBox.height * scale) > MAX_PIXELS) {
            throw IOException("Page exceeds the $MAX_PIXELS pixel render limit at $dpi DPI")
        }
        // PDFBox's image path handles blending/transparency more faithfully than renderPageToGraphics.
        val image = renderer.renderImageWithDPI(region.page - 1, dpi.toFloat())
        try {
            val x0 = floor(crop.x * scale).toInt().coerceIn(0, image.width - 1)
            val y0 = floor(crop.y * scale).toInt().coerceIn(0, image.height - 1)
            val x1 = ceil(crop.maxX * scale).toInt().coerceIn(x0 + 1, image.width)
            val y1 = ceil(crop.maxY * scale).toInt().coerceIn(y0 + 1, image.height)
            if (!ImageIO.write(image.getSubimage(x0, y0, x1 - x0, y1 - y0), "png", target.toFile())) {
                throw IOException("PNG encoder unavailable")
            }
        } finally {
            image.flush()
        }
    }

    private fun crop(region: PdfFigureRegion, padding: Double): Rectangle2D.Double {
        if (region.page !in 1..document.numberOfPages) throw IOException("Figure page is outside the PDF")
        val values = listOf(region.left, region.top, region.right, region.bottom)
        if (values.any { !it.isFinite() || it < -0.001 || it > 1.001 } ||
            region.right <= region.left || region.bottom <= region.top) throw IOException("Invalid figure coordinates")
        val page = document.getPage(region.page - 1)
        val rotated = page.rotation.mod(180) == 90
        val width = (if (rotated) page.cropBox.height else page.cropBox.width).toDouble()
        val height = (if (rotated) page.cropBox.width else page.cropBox.height).toDouble()
        if (!width.isFinite() || !height.isFinite() || width <= 0 || height <= 0 || width * height > MAX_PAGE_POINTS) {
            throw IOException("PDF page is too large for figure export")
        }
        val rw = region.referenceWidth
        val rh = region.referenceHeight
        if (rw != null && rh != null && (rw <= 0 || rh <= 0 || !rw.isFinite() || !rh.isFinite() ||
                    abs((rw / rh) / (width / height) - 1) > 0.02)
        ) {
            throw IOException("OCR page dimensions do not match the original PDF orientation")
        }
        val x0 = (region.left * width - padding).coerceIn(0.0, width)
        val y0 = (region.top * height - padding).coerceIn(0.0, height)
        val x1 = (region.right * width + padding).coerceIn(0.0, width)
        val y1 = (region.bottom * height + padding).coerceIn(0.0, height)
        if (x1 - x0 < 0.5 || y1 - y0 < 0.5) throw IOException("Figure coordinates describe an empty region")
        return Rectangle2D.Double(x0, y0, x1 - x0, y1 - y0)
    }

    private fun hasComplexTransparency(resources: PDResources?, seen: MutableSet<COSDictionary>): Boolean {
        if (resources == null || !seen.add(resources.cosObject)) return false
        for (name in resources.extGStateNames) {
            val state = resources.getExtGState(name)
            if (state.softMask != null || state.blendMode !in listOf(
                    null,
                    BlendMode.NORMAL,
                    BlendMode.COMPATIBLE
                )
            ) return true
        }
        for (name in resources.xObjectNames) {
            val form = resources.getXObject(name) as? PDFormXObject ?: continue
            if (form.cosObject.containsKey(COSName.GROUP) || hasComplexTransparency(form.resources, seen)) return true
        }
        return false
    }

    override fun close() = document.close()

    companion object {
        private const val MAX_PIXELS = 40_000_000L
        private const val MAX_PAGE_POINTS = 10_000_000.0
    }
}
