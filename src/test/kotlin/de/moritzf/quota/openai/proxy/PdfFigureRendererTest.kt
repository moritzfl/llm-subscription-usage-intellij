package de.moritzf.quota.openai.proxy

import de.moritzf.quota.openai.proxy.pdf.PdfFigureRegion
import de.moritzf.quota.openai.proxy.pdf.PdfFigureRenderer
import de.moritzf.quota.shared.DocumentImageFormat
import de.moritzf.quota.shared.DocumentImageOptions
import de.moritzf.quota.shared.DocumentImageWriter
import de.moritzf.quota.shared.ProviderDocumentImage
import java.awt.Color
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.apache.batik.transcoder.TranscoderInput
import org.apache.batik.transcoder.TranscoderOutput
import org.apache.batik.transcoder.image.PNGTranscoder
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.blend.BlendMode
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSFloat
import org.apache.pdfbox.cos.COSBoolean
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.common.function.PDFunctionType2
import org.apache.pdfbox.pdmodel.common.function.PDFunctionType3
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType2
import org.junit.jupiter.api.io.TempDir

class PdfFigureRendererTest {
    @TempDir lateinit var directory: Path

    @Test
    fun svgAndPngCropsMatchOriginalForRotationsAndOffsetCropBoxes() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val document = quadrantPdf(rotation)
            val reference = PDFRenderer(document).renderImageWithDPI(0, 72f)
            val region = PdfFigureRegion(1, 0.0, 0.0, 0.75, 0.75,
                reference.width.toDouble(), reference.height.toDouble())
            PdfFigureRenderer(document).use { renderer ->
                val png = directory.resolve("$rotation.png")
                val svg = directory.resolve("$rotation.svg")
                renderer.renderPng(region, png, 72, 0.0)
                renderer.renderSvg(region, svg, 0.0)
                val xml = Files.readString(svg)
                assertTrue(xml.contains("viewBox="))
                assertFalse(xml.contains("data:image"), "Vector-only PDF must remain vector-only")
                val pngImage = ImageIO.read(png.toFile())
                val svgImage = ByteArrayOutputStream().use { bytes ->
                    PNGTranscoder().transcode(TranscoderInput(svg.toUri().toString()), TranscoderOutput(bytes))
                    ImageIO.read(bytes.toByteArray().inputStream())
                }
                assertEquals(pngImage.width, svgImage.width)
                assertEquals(pngImage.height, svgImage.height)
                for (x in listOf(10, pngImage.width - 10)) for (y in listOf(10, pngImage.height - 10)) {
                    assertEquals(reference.getRGB(x, y), pngImage.getRGB(x, y), "PNG crop rotation=$rotation at $x/$y")
                    assertEquals(reference.getRGB(x, y), svgImage.getRGB(x, y), "SVG crop rotation=$rotation at $x/$y")
                }
            }
        }
    }

    @Test
    fun dpiAndPaddingChangeRasterResolutionWithoutReusingLowerResolution() {
        PdfFigureRenderer(quadrantPdf(0)).use { renderer ->
            val region = PdfFigureRegion(1, 0.25, 0.25, 0.75, 0.75)
            val low = directory.resolve("low.png")
            val high = directory.resolve("high.png")
            renderer.renderPng(region, low, 300, 2.0)
            renderer.renderPng(region, high, 600, 2.0)
            assertEquals(434, ImageIO.read(low.toFile()).width)
            assertEquals(867, ImageIO.read(high.toFile()).width)
            assertEquals(451, ImageIO.read(high.toFile()).height)
        }
    }

    @Test
    fun rejectsInvalidCoordinatesAndInconsistentPageOrientation() {
        PdfFigureRenderer(quadrantPdf(0)).use { renderer ->
            for (region in listOf(
                PdfFigureRegion(0, 0.0, 0.0, 1.0, 1.0),
                PdfFigureRegion(1, Double.NaN, 0.0, 1.0, 1.0),
                PdfFigureRegion(1, 0.8, 0.2, 0.1, 0.9),
                PdfFigureRegion(1, 0.0, 0.0, 500.0, 900.0),
                PdfFigureRegion(1, 0.0, 0.0, 1.0, 1.0, 100.0, 200.0),
            )) {
                assertFailsWith<IOException> { renderer.renderSvg(region, directory.resolve("bad.svg"), 0.0) }
            }
        }
    }

    @Test
    fun axialGradientKeepsColorsInSvg() = assertGradient(stitched = false)

    @Test
    fun stitchedGradientKeepsColorsInSvg() = assertGradient(stitched = true)

    private fun assertGradient(stitched: Boolean) {
        val document = PDDocument()
        val page = PDPage(PDRectangle(200f, 100f))
        document.addPage(page)
        fun numbers(vararg values: Float) = COSArray().apply { values.forEach { add(COSFloat(it)) } }
        val function = PDFunctionType2(COSDictionary().apply {
            setInt(COSName.FUNCTION_TYPE, 2)
            setItem(COSName.DOMAIN, numbers(0f, 1f))
            setItem(COSName.C0, numbers(1f, 0f, 0f))
            setItem(COSName.C1, numbers(0f, 0f, 1f))
            setFloat(COSName.N, 1f)
        })
        val shading = PDShadingType2(COSDictionary()).apply {
            shadingType = 2
            colorSpace = PDDeviceRGB.INSTANCE
            coords = numbers(0f, 0f, 200f, 0f)
            extend = COSArray().apply { add(COSBoolean.TRUE); add(COSBoolean.TRUE) }
            setFunction(if (!stitched) function else PDFunctionType3(COSDictionary().apply {
                setInt(COSName.FUNCTION_TYPE, 3)
                setItem(COSName.DOMAIN, numbers(0f, 1f))
                setItem(COSName.FUNCTIONS, COSArray().apply { add(function.cosObject); add(function.cosObject) })
                setItem(COSName.BOUNDS, numbers(0.4f))
                setItem(COSName.ENCODE, numbers(0f, 1f, 1f, 0f))
            }))
        }
        PDPageContentStream(document, page).use { it.shadingFill(shading) }
        val reference = PDFRenderer(document).renderImageWithDPI(0, 72f)
        val svg = directory.resolve("gradient.svg")
        PdfFigureRenderer(document).use { it.renderSvg(PdfFigureRegion(1, 0.0, 0.0, 1.0, 1.0), svg, 0.0) }
        val actual = ByteArrayOutputStream().use { bytes ->
            PNGTranscoder().transcode(TranscoderInput(svg.toUri().toString()), TranscoderOutput(bytes))
            ImageIO.read(bytes.toByteArray().inputStream())
        }
        for (x in listOf(20, 100, 180)) {
            val expected = Color(reference.getRGB(x, 50))
            val exported = Color(actual.getRGB(x, 50))
            assertTrue(kotlin.math.abs(expected.red - exported.red) < 4 &&
                kotlin.math.abs(expected.blue - exported.blue) < 4, "Gradient mismatch at $x: $expected / $exported")
        }
    }

    @Test
    fun complexTransparencyFallsBackToPngAndReportsReason() {
        val document = quadrantPdf(0)
        val page = document.getPage(0)
        page.resources = page.resources ?: PDResources()
        page.resources.add(PDExtendedGraphicsState().apply { blendMode = BlendMode.MULTIPLY })
        DocumentImageWriter(directory.resolve("doc.md"), DocumentImageOptions(), { PdfFigureRenderer(document) }).use { writer ->
            val result = writer.write(1, 1, PdfFigureRegion(1, 0.0, 0.0, 1.0, 1.0)) { error("Provider image not needed") }
            assertTrue(result!!.endsWith(".png"))
            assertTrue(writer.warnings.single().contains("transparency"))
            assertTrue(Files.isRegularFile(Path.of(writer.imageFiles.single())))
        }
        Files.list(directory).use { assertEquals(0L, it.count(), "Uncommitted images must be cleaned") }
    }

    @Test
    fun missingCoordinatesFallBackAndProviderModeNeverOpensPdf() {
        for (format in listOf(DocumentImageFormat.SVG, DocumentImageFormat.PROVIDER)) {
            DocumentImageWriter(directory.resolve("$format.md"), DocumentImageOptions(format), {
                if (format == DocumentImageFormat.PROVIDER) error("Provider mode must not open PDF")
                PdfFigureRenderer(quadrantPdf(0))
            }).use { writer ->
                assertTrue(writer.write(7, 2, null) { ProviderDocumentImage(byteArrayOf(1, 2, 3), "jpeg") }!!.endsWith(".jpeg"))
                assertEquals(format == DocumentImageFormat.SVG, writer.warnings.isNotEmpty())
            }
        }
    }

    private fun quadrantPdf(rotation: Int): PDDocument {
        val document = PDDocument()
        val page = PDPage(PDRectangle(400f, 400f)).apply {
            cropBox = PDRectangle(50f, 75f, 200f, 100f)
            this.rotation = rotation
        }
        document.addPage(page)
        PDPageContentStream(document, page).use { content ->
            for ((index, color) in listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).withIndex()) {
                content.setNonStrokingColor(color)
                content.addRect(50f + (index % 2) * 100f, 75f + (if (index < 2) 50f else 0f), 100f, 50f)
                content.fill()
            }
        }
        return document
    }
}
