package de.moritzf.quota.openai.proxy.pdf

import java.awt.Color
import java.awt.GradientPaint
import java.awt.Paint
import java.awt.TexturePaint
import java.io.IOException
import org.apache.batik.svggen.DefaultExtensionHandler
import org.apache.batik.svggen.SVGGeneratorContext
import org.apache.batik.svggen.SVGPaintDescriptor
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSBoolean
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSBase
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import org.apache.pdfbox.pdmodel.graphics.shading.AxialShadingPaint
import kotlin.math.roundToInt

/** Batik otherwise silently ignores PDFBox Paint implementations, reusing the previous color. */
internal class PdfSvgPaintHandler : DefaultExtensionHandler() {
    override fun handlePaint(paint: Paint, context: SVGGeneratorContext): SVGPaintDescriptor? {
        if (paint is Color || paint is GradientPaint || paint is TexturePaint) return null
        if (paint !is AxialShadingPaint) throw IOException("Unsupported SVG paint: ${paint.javaClass.simpleName}")
        val shading = paint.shading
        if (shading.colorSpace !is PDDeviceRGB && shading.colorSpace !is PDDeviceGray) {
            throw IOException("SVG gradient does not support ${shading.colorSpace.name}")
        }
        val knots = linearKnots(shading.cosObject.getDictionaryObject(COSName.FUNCTION), 0)
        val coords = shading.coords?.toFloatArray()?.takeIf { it.size == 4 && it.all(Float::isFinite) }
            ?: throw IOException("Invalid PDF gradient coordinates")
        if (coords[0] == coords[2] && coords[1] == coords[3]) throw IOException("Degenerate PDF gradient")
        val extend = shading.extend
        if (extend == null || extend.size() != 2 || extend.getObject(0) != COSBoolean.TRUE || extend.getObject(1) != COSBoolean.TRUE) {
            throw IOException("Non-extended PDF gradient needs raster rendering")
        }
        val domain = shading.domain?.toFloatArray() ?: floatArrayOf(0f, 1f)
        if (domain.size != 2 || domain.any { !it.isFinite() } || domain[1] <= domain[0]) throw IOException("Invalid PDF gradient domain")
        if (domain[0] < 0f || domain[1] > 1f) throw IOException("Non-unit PDF gradient domain needs raster rendering")
        val id = context.idGenerator.generateID("pdfGradient")
        val gradient = context.domFactory.createElementNS(SVG_NS, "linearGradient")
        gradient.setAttribute("id", id)
        gradient.setAttribute("gradientUnits", "userSpaceOnUse")
        gradient.setAttribute("color-interpolation", "sRGB")
        gradient.setAttribute("x1", coords[0].toString())
        gradient.setAttribute("y1", coords[1].toString())
        gradient.setAttribute("x2", coords[2].toString())
        gradient.setAttribute("y2", coords[3].toString())
        val transform = paint.matrix.createAffineTransform()
        gradient.setAttribute(
            "gradientTransform", "matrix(${transform.scaleX} ${transform.shearY} " +
                    "${transform.shearX} ${transform.scaleY} ${transform.translateX} ${transform.translateY})"
        )
        val stops = (knots.filter { it > domain[0] && it < domain[1] } + domain.toList()).distinct().sorted()
        fun addStop(position: Float, sample: Float) {
            val color = shading.colorSpace.toRGB(shading.evalFunction(sample))
            val rgb = color.map { (it.coerceIn(0f, 1f) * 255).roundToInt() }
            val stop = context.domFactory.createElementNS(SVG_NS, "stop")
            stop.setAttribute("offset", ((position - domain[0]) / (domain[1] - domain[0])).toString())
            stop.setAttribute("stop-color", "rgb(${rgb[0]},${rgb[1]},${rgb[2]})")
            gradient.appendChild(stop)
        }
        for (position in stops) {
            // Duplicate stops preserve discontinuities between stitched linear functions.
            if (position > domain[0] && position < domain[1]) {
                addStop(position, Math.nextDown(position))
                addStop(position, Math.nextUp(position))
            } else addStop(position, position)
        }
        return SVGPaintDescriptor("url(#$id)", "1", gradient)
    }

    private fun linearKnots(value: COSBase?, depth: Int): List<Float> {
        if (depth > 12) throw IOException("PDF gradient function nesting is too deep")
        if (value is COSArray) {
            if (value.size() !in 1..4) throw IOException("Unsupported gradient component count")
            return (0 until value.size()).flatMap { linearKnots(value.getObject(it), depth + 1) }
        }
        val function = value as? COSDictionary ?: throw IOException("Unsupported PDF gradient function")
        val domain = function.getCOSArray(COSName.DOMAIN)?.toFloatArray()
            ?.takeIf { it.size == 2 && it.all(Float::isFinite) && it[1] > it[0] }
            ?: throw IOException("Invalid PDF gradient function domain")
        if (domain[0] != 0f || domain[1] != 1f) throw IOException("Non-unit gradient function domain needs raster rendering")
        val range = function.getCOSArray(COSName.RANGE)?.toFloatArray()
        if (range != null && (range.size % 2 != 0 || range.any { !it.isFinite() } ||
                range.toList().chunked(2).any { it[0] > 0f || it[1] < 1f })) {
            throw IOException("Clipped gradient function range needs raster rendering")
        }
        when (function.getInt(COSName.FUNCTION_TYPE)) {
            2 -> {
                if (function.getFloat(COSName.N) != 1f) throw IOException("Nonlinear PDF gradient needs raster rendering")
                val colors = (function.getCOSArray(COSName.C0)?.toFloatArray() ?: floatArrayOf()) +
                    (function.getCOSArray(COSName.C1)?.toFloatArray() ?: floatArrayOf())
                if (colors.any { !it.isFinite() || it !in 0f..1f }) throw IOException("Out-of-range gradient colors need raster rendering")
                return domain.toList()
            }

            3 -> {
                val functions =
                    function.getCOSArray(COSName.FUNCTIONS) ?: throw IOException("Missing stitched gradient functions")
                val bounds = function.getCOSArray(COSName.BOUNDS)?.toFloatArray() ?: floatArrayOf()
                val encode = function.getCOSArray(COSName.ENCODE)?.toFloatArray() ?: floatArrayOf()
                if (functions.size() !in 1..256 || bounds.size != functions.size() - 1 || encode.size != functions.size() * 2 ||
                    bounds.any { !it.isFinite() || it <= domain[0] || it >= domain[1] } || encode.any { !it.isFinite() || it !in 0f..1f }
                ) {
                    throw IOException("Invalid stitched gradient")
                }
                val edges = listOf(domain[0]) + bounds.toList() + domain[1]
                if (edges.zipWithNext().any { (a, b) -> a >= b }) throw IOException("Unordered gradient bounds")
                val knots = edges.toMutableList()
                for (index in 0 until functions.size()) {
                    val from = encode[index * 2]
                    val to = encode[index * 2 + 1]
                    if (from == to) throw IOException("Degenerate gradient encoding")
                    for (knot in linearKnots(functions.getObject(index), depth + 1)) {
                        val position = (knot - from) / (to - from)
                        if (position in 0f..1f) knots += edges[index] + position * (edges[index + 1] - edges[index])
                        if (knots.size > 2048) throw IOException("PDF gradient has too many stops")
                    }
                }
                return knots
            }

            else -> throw IOException("PDF gradient function type ${function.getInt(COSName.FUNCTION_TYPE)} needs raster rendering")
        }
    }

    companion object {
        private const val SVG_NS = "http://www.w3.org/2000/svg"
    }
}
