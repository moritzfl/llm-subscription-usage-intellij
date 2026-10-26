package de.moritzf.quota.shared.document

import java.awt.Color
import java.awt.GradientPaint
import java.awt.Paint
import java.awt.TexturePaint
import java.io.IOException
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.roundToInt
import org.apache.batik.svggen.DefaultExtensionHandler
import org.apache.batik.svggen.SVGGeneratorContext
import org.apache.batik.svggen.SVGPaintDescriptor
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSBase
import org.apache.pdfbox.cos.COSBoolean
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.graphics.shading.AxialShadingPaint
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType2

/**
 * Converts extended axial shadings to SVG gradients. Batik silently reuses the previous color for
 * other PDFBox paints, so [PdfSvgGraphics] rasterizes fills that [isVector] rejects.
 */
internal class PdfSvgPaintHandler : DefaultExtensionHandler() {
    private val gradients = IdentityHashMap<Paint, SVGPaintDescriptor?>()

    fun isVector(paint: Paint, context: SVGGeneratorContext): Boolean =
        paint is Color ||
            paint is GradientPaint ||
            paint is TexturePaint ||
            handlePaint(paint, context) != null

    override fun handlePaint(paint: Paint, context: SVGGeneratorContext): SVGPaintDescriptor? {
        if (paint !is AxialShadingPaint) return null
        if (!gradients.containsKey(paint)) gradients[paint] = gradient(paint, context)
        return gradients[paint]
    }

    private fun gradient(
        paint: AxialShadingPaint,
        context: SVGGeneratorContext,
    ): SVGPaintDescriptor? {
        val shading = paint.shading
        val coords =
            shading.coords?.toFloatArray()?.takeIf { it.size == 4 && it.all(Float::isFinite) }
                ?: return null
        if (coords[0] == coords[2] && coords[1] == coords[3]) return null
        // SVG pads both ends; PDF leaves non-extended ends unpainted.
        val extend = shading.extend
        if (
            extend == null ||
                extend.size() != 2 ||
                extend.getObject(0) != COSBoolean.TRUE ||
                extend.getObject(1) != COSBoolean.TRUE
        )
            return null
        val domain = shading.domain?.toFloatArray() ?: floatArrayOf(0f, 1f)
        if (domain.size != 2 || domain.any { !it.isFinite() } || domain[1] <= domain[0]) return null
        val stops =
            try {
                stops(shading, domain[0], domain[1])
            } catch (exception: IOException) {
                return null
            } catch (exception: RuntimeException) {
                // Malformed functions or color spaces; PDFBox still rasterizes them.
                return null
            }
        val id = context.idGenerator.generateID("pdfGradient")
        val gradient = context.domFactory.createElementNS(SVG_NS, "linearGradient")
        gradient.setAttribute("id", id)
        gradient.setAttribute("gradientUnits", "userSpaceOnUse")
        gradient.setAttribute("color-interpolation", "sRGB")
        gradient.setAttribute("x1", context.doubleString(coords[0].toDouble()))
        gradient.setAttribute("y1", context.doubleString(coords[1].toDouble()))
        gradient.setAttribute("x2", context.doubleString(coords[2].toDouble()))
        gradient.setAttribute("y2", context.doubleString(coords[3].toDouble()))
        val transform = paint.matrix.createAffineTransform()
        gradient.setAttribute(
            "gradientTransform",
            listOf(
                    transform.scaleX,
                    transform.shearY,
                    transform.shearX,
                    transform.scaleY,
                    transform.translateX,
                    transform.translateY,
                )
                .joinToString(" ", "matrix(", ")", transform = context::doubleString),
        )
        for (stop in stops) {
            val rgb = stop.rgb.map { (it * 255).roundToInt() }
            val element = context.domFactory.createElementNS(SVG_NS, "stop")
            element.setAttribute("offset", context.doubleString(stop.offset.toDouble()))
            element.setAttribute("stop-color", "rgb(${rgb[0]},${rgb[1]},${rgb[2]})")
            gradient.appendChild(element)
        }
        return SVGPaintDescriptor("url(#$id)", "1", gradient)
    }

    private class Stop(val offset: Float, val rgb: List<Float>)

    /**
     * Samples any function type and color space, as PDFBox's own shading context does, then drops
     * stops that linear interpolation reproduces within half an 8-bit color step.
     */
    private fun stops(shading: PDShadingType2, t0: Float, t1: Float): List<Stop> {
        fun sample(offset: Float, t: Float) =
            Stop(
                offset,
                shading.colorSpace.toRGB(shading.evalFunction(t)).map { it.coerceIn(0f, 1f) },
            )
        val bounds =
            stitchingBounds(shading.cosObject.getDictionaryObject(COSName.FUNCTION)).filter {
                it > t0 && it < t1
            }
        val uniform =
            (0..SAMPLES).map { index ->
                val offset = index.toFloat() / SAMPLES
                offset to t0 + offset * (t1 - t0)
            }
        // Sample both sides of each stitch at one offset so discontinuities stay hard edges.
        val edges = bounds.flatMap {
            val offset = (it - t0) / (t1 - t0)
            listOf(offset to Math.nextDown(it), offset to Math.nextUp(it))
        }
        val stops =
            (uniform.filter { it.second !in bounds } + edges)
                .sortedBy { it.second }
                .map { (offset, t) -> sample(offset, t) }
        val kept = mutableListOf(stops.first())
        var start = 0
        for (end in 2..stops.lastIndex) {
            if (!interpolates(stops, start, end)) {
                kept += stops[end - 1]
                start = end - 1
            }
        }
        kept += stops.last()
        return kept
    }

    private fun interpolates(stops: List<Stop>, start: Int, end: Int): Boolean {
        val from = stops[start]
        val to = stops[end]
        val span = to.offset - from.offset
        if (span <= 0f) return false
        for (index in start + 1 until end) {
            val fraction = (stops[index].offset - from.offset) / span
            for (channel in 0..2) {
                val expected = from.rgb[channel] + (to.rgb[channel] - from.rgb[channel]) * fraction
                if (abs(expected - stops[index].rgb[channel]) > MAX_COLOR_ERROR) return false
            }
        }
        return true
    }

    private fun stitchingBounds(function: COSBase?): List<Float> =
        when (function) {
            is COSArray ->
                (0 until function.size()).flatMap { stitchingBounds(function.getObject(it)) }
            is COSDictionary ->
                if (function.getInt(COSName.FUNCTION_TYPE) != 3) emptyList()
                else
                    function
                        .getCOSArray(COSName.BOUNDS)
                        ?.toFloatArray()
                        ?.filter(Float::isFinite)
                        .orEmpty()
            else -> emptyList()
        }

    companion object {
        private const val SVG_NS = "http://www.w3.org/2000/svg"
        private const val SAMPLES = 1024
        private const val MAX_COLOR_ERROR = 0.5f / 255
    }
}
