package de.moritzf.quota.shared.document

import java.awt.Graphics
import java.awt.Image
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.awt.image.ImageObserver
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import org.apache.batik.svggen.SVGGeneratorContext
import org.apache.batik.svggen.SVGGraphics2D

/**
 * Keeps vector output where SVG has an equivalent paint and embeds only the affected fill as a
 * canvas-resolution raster otherwise (tiling patterns, non-axial shadings, soft-mask paints).
 */
internal class PdfSvgGraphics : SVGGraphics2D {
    private val paints: PdfSvgPaintHandler
    /** Canvas units per PDF point. */
    private val pointScale: Double
    /** Batik's create() copies share the DOM but not the canvas size. */
    private val owner: PdfSvgGraphics?
    var rasterizedFills = 0
        private set

    constructor(
        context: SVGGeneratorContext,
        paints: PdfSvgPaintHandler,
        pointScale: Double,
    ) : super(context, true) {
        this.paints = paints
        this.pointScale = pointScale
        owner = null
    }

    private constructor(other: PdfSvgGraphics) : super(other) {
        paints = other.paints
        pointScale = other.pointScale
        owner = other.owner ?: other
    }

    override fun create(): Graphics = PdfSvgGraphics(this)

    override fun fill(shape: Shape) {
        if (paints.isVector(paint, generatorContext)) super.fill(shape) else rasterize(shape)
    }

    override fun draw(shape: Shape) {
        if (paints.isVector(paint, generatorContext)) super.draw(shape)
        else rasterize(stroke.createStrokedShape(shape))
    }

    /**
     * PDFBox requests nearest-neighbour scaling for upscaled non-interpolated images. Decide that
     * at 72 DPI as before, so viewers keep smoothing scans that are only upscaled on this canvas.
     */
    override fun drawImage(
        image: Image,
        xform: AffineTransform?,
        observer: ImageObserver?,
    ): Boolean {
        val nearest = RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
        if (xform == null || getRenderingHint(RenderingHints.KEY_INTERPOLATION) != nearest)
            return super.drawImage(image, xform, observer)
        val device = AffineTransform(transform).apply { concatenate(xform) }
        if (
            hypot(device.scaleX, device.shearY) >= pointScale ||
                hypot(device.shearX, device.scaleY) >= pointScale
        )
            return super.drawImage(image, xform, observer)
        setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BICUBIC,
        )
        try {
            return super.drawImage(image, xform, observer)
        } finally {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, nearest)
        }
    }

    private fun rasterize(shape: Shape) {
        val root = owner ?: this
        var area =
            transform
                .createTransformedShape(shape)
                .bounds2D
                .createIntersection(Rectangle(root.svgCanvasSize))
        clip?.let { area = area.createIntersection(transform.createTransformedShape(it).bounds2D) }
        if (area.isEmpty) return
        val x = floor(area.minX).toInt()
        val y = floor(area.minY).toInt()
        val image =
            BufferedImage(
                ceil(area.maxX).toInt() - x,
                ceil(area.maxY).toInt() - y,
                BufferedImage.TYPE_INT_ARGB,
            )
        val raster = image.createGraphics()
        try {
            raster.setRenderingHints(renderingHints)
            raster.translate(-x, -y)
            raster.transform(transform)
            raster.paint = paint
            raster.fill(shape)
        } finally {
            raster.dispose()
        }
        // Clip and opacity stay in the SVG; one image pixel covers one canvas unit.
        val userTransform = transform
        try {
            transform = AffineTransform()
            drawImage(image, x, y, null)
        } finally {
            transform = userTransform
        }
        root.rasterizedFills++
    }
}
