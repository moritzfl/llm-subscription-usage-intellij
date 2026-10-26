package de.moritzf.quota.shared.document

import java.awt.BasicStroke
import java.awt.Graphics
import java.awt.Image
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.NoninvertibleTransformException
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.awt.image.ImageObserver
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt
import org.apache.batik.svggen.SVGGeneratorContext
import org.apache.batik.svggen.SVGGraphics2D

/**
 * Keeps vector output where SVG has an equivalent paint and embeds only the affected fill as a
 * canvas-resolution raster otherwise (tiling patterns, non-axial shadings, soft-mask paints).
 * PDFBox draws the whole page; content outside the canvas and clip is dropped so a figure's SVG
 * holds only what it shows.
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
        if (!isVisible(shape.bounds2D)) return
        if (paints.isVector(paint, generatorContext)) super.fill(shape) else rasterize(shape)
    }

    override fun draw(shape: Shape) {
        val stroke = stroke
        if (stroke !is BasicStroke || !paints.isVector(paint, generatorContext)) {
            fill(stroke.createStrokedShape(shape))
            return
        }
        // Miter joins and square caps reach furthest beyond the path.
        val reach = stroke.lineWidth / 2.0 * max(stroke.miterLimit.toDouble(), sqrt(2.0))
        val bounds = shape.bounds2D
        bounds.setRect(
            bounds.x - reach,
            bounds.y - reach,
            bounds.width + 2 * reach,
            bounds.height + 2 * reach,
        )
        if (isVisible(bounds)) super.draw(shape)
    }

    /** Embeds only the visible pixels (plus one for edge interpolation) at source resolution. */
    override fun drawImage(
        image: Image,
        xform: AffineTransform?,
        observer: ImageObserver?,
    ): Boolean {
        val device = AffineTransform(transform).apply { xform?.let(::concatenate) }
        val width = image.getWidth(null)
        val height = image.getHeight(null)
        if (width <= 0 || height <= 0 || image !is BufferedImage)
            return drawScaled(image, xform, device, observer)
        val visible = visibleArea(Rectangle(width, height), device)
        if (visible.isEmpty) return true
        val pixels =
            try {
                device.createInverse().createTransformedShape(visible).bounds2D
            } catch (exception: NoninvertibleTransformException) {
                return drawScaled(image, xform, device, observer)
            }
        val x0 = (floor(pixels.minX).toInt() - 1).coerceIn(0, width - 1)
        val y0 = (floor(pixels.minY).toInt() - 1).coerceIn(0, height - 1)
        val x1 = (ceil(pixels.maxX).toInt() + 1).coerceIn(x0 + 1, width)
        val y1 = (ceil(pixels.maxY).toInt() + 1).coerceIn(y0 + 1, height)
        if (x0 == 0 && y0 == 0 && x1 == width && y1 == height)
            return drawScaled(image, xform, device, observer)
        val offset =
            AffineTransform(xform ?: AffineTransform()).apply {
                translate(x0.toDouble(), y0.toDouble())
            }
        return drawScaled(image.getSubimage(x0, y0, x1 - x0, y1 - y0), offset, device, observer)
    }

    /**
     * PDFBox requests nearest-neighbour scaling for upscaled non-interpolated images. Decide that
     * at 72 DPI as before, so viewers keep smoothing scans that are only upscaled on this canvas.
     */
    private fun drawScaled(
        image: Image,
        xform: AffineTransform?,
        device: AffineTransform,
        observer: ImageObserver?,
    ): Boolean {
        val nearest = RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
        if (xform == null || getRenderingHint(RenderingHints.KEY_INTERPOLATION) != nearest)
            return super.drawImage(image, xform, observer)
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

    private fun isVisible(bounds: Rectangle2D) = !visibleArea(bounds, transform).isEmpty

    /**
     * Canvas-space part of [bounds] inside the canvas and the current clip. One canvas unit of
     * slack keeps hairlines and antialiased edges at the border.
     */
    private fun visibleArea(bounds: Rectangle2D, toCanvas: AffineTransform): Rectangle2D {
        val area = toCanvas.createTransformedShape(bounds).bounds2D
        area.setRect(area.x - 1, area.y - 1, area.width + 2, area.height + 2)
        return clipToVisible(area)
    }

    private fun clipToVisible(area: Rectangle2D): Rectangle2D {
        var visible = area.createIntersection(Rectangle((owner ?: this).svgCanvasSize))
        clip?.let {
            visible = visible.createIntersection(transform.createTransformedShape(it).bounds2D)
        }
        return visible
    }

    private fun rasterize(shape: Shape) {
        val area = clipToVisible(transform.createTransformedShape(shape).bounds2D)
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
        (owner ?: this).rasterizedFills++
    }
}
