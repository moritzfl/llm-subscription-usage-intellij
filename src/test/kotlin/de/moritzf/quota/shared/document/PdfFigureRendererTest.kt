package de.moritzf.quota.shared.document

import de.moritzf.quota.shared.DocumentImageFormat
import de.moritzf.quota.shared.DocumentImageOptions
import de.moritzf.quota.shared.DocumentImageWriter
import de.moritzf.quota.shared.ProviderDocumentImage
import java.awt.Color
import java.awt.color.ColorSpace
import java.awt.color.ICC_Profile
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.apache.batik.transcoder.TranscoderInput
import org.apache.batik.transcoder.TranscoderOutput
import org.apache.batik.transcoder.image.PNGTranscoder
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSBoolean
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSFloat
import org.apache.pdfbox.cos.COSInteger
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSStream
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.PDPatternContentStream
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.common.function.PDFunction
import org.apache.pdfbox.pdmodel.common.function.PDFunctionType2
import org.apache.pdfbox.pdmodel.common.function.PDFunctionType3
import org.apache.pdfbox.pdmodel.graphics.blend.BlendMode
import org.apache.pdfbox.pdmodel.graphics.color.PDColor
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import org.apache.pdfbox.pdmodel.graphics.color.PDICCBased
import org.apache.pdfbox.pdmodel.graphics.color.PDPattern
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType2
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.io.TempDir

class PdfFigureRendererTest {
    @TempDir lateinit var directory: Path

    @Test
    fun rasterImagesExportAsSvgWithAnIsolatedPluginClassLoader() {
        val document = PDDocument()
        document.addPage(PDPage(PDRectangle(200f, 100f)))
        val image =
            BufferedImage(20, 10, BufferedImage.TYPE_INT_ARGB).apply {
                createGraphics().let { graphics ->
                    try {
                        graphics.color = Color.RED
                        graphics.fillRect(0, 0, 10, 10)
                        graphics.color = Color.BLUE
                        graphics.fillRect(10, 0, 10, 10)
                    } finally {
                        graphics.dispose()
                    }
                }
                setRGB(19, 0, Color(0, 255, 0, 80).rgb)
            }
        PDPageContentStream(document, document.getPage(0)).use {
            it.drawImage(LosslessFactory.createFromImage(document, image), 0f, 0f, 200f, 100f)
        }
        val loader =
            object : ClassLoader(javaClass.classLoader) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> {
                    if (
                        !name.startsWith("org.apache.batik.") &&
                            !name.startsWith("de.moritzf.quota.shared.document.")
                    ) {
                        return super.loadClass(name, resolve)
                    }
                    synchronized(getClassLoadingLock(name)) {
                        val loaded =
                            findLoadedClass(name)
                                ?: parent
                                    .getResourceAsStream(name.replace('.', '/') + ".class")!!
                                    .use {
                                        val bytes = it.readBytes()
                                        defineClass(name, bytes, 0, bytes.size)
                                    }
                        if (resolve) resolveClass(loaded)
                        return loaded
                    }
                }
            }
        val rendererClass = loader.loadClass(PdfFigureRenderer::class.java.name)
        val regionClass = loader.loadClass(PdfFigureRegion::class.java.name)
        val region =
            regionClass.constructors
                .single { it.parameterCount == 7 }
                .newInstance(1, 0.0, 0.0, 1.0, 1.0, null, null)
        val target = directory.resolve("raster.svg")
        val renderer =
            rendererClass.getConstructor(PDDocument::class.java).newInstance(document)
                as AutoCloseable
        renderer.use {
            rendererClass
                .getMethod(
                    "renderSvg",
                    regionClass,
                    Path::class.java,
                    Double::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                .invoke(renderer, region, target, 0.0, 300)
        }
        assertTrue(Files.readString(target).contains("data:image/png;base64,"))
        val embedded = embeddedPng(target)
        assertEquals(Color.RED.rgb, embedded.getRGB(2, 2))
        assertEquals(Color.BLUE.rgb, embedded.getRGB(12, 2))
        assertEquals(
            image.getRGB(19, 0),
            embedded.getRGB(19, 0),
            "Embedded PNG must preserve alpha",
        )
    }

    @Test
    fun svgKeepsEmbeddedRasterPixelsAtOriginalResolution() {
        val image =
            BufferedImage(600, 300, BufferedImage.TYPE_INT_RGB).apply {
                for (y in 0 until height) for (x in 0 until width) {
                    setRGB(x, y, if ((x / 2 + y / 2) % 2 == 0) Color.BLACK.rgb else Color.WHITE.rgb)
                }
            }
        val document = PDDocument().apply { addPage(PDPage(PDRectangle(200f, 100f))) }
        PDPageContentStream(document, document.getPage(0)).use {
            it.drawImage(LosslessFactory.createFromImage(document, image), 0f, 0f, 200f, 100f)
        }
        val target = directory.resolve("high-resolution.svg")
        val png = directory.resolve("after-svg.png")
        val reference = PDFRenderer(document).renderImageWithDPI(0, 72f)
        PdfFigureRenderer(document).use {
            val region = PdfFigureRegion(1, 0.0, 0.0, 1.0, 1.0)
            it.renderSvg(region, target, 0.0, 300)
            it.renderPng(region, png, 72, 0.0)
        }
        val embedded = embeddedPng(target)
        assertEquals(image.width, embedded.width, "SVG must not bake in the 72 DPI render scale")
        assertEquals(image.height, embedded.height)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            assertEquals(image.getRGB(x, y), embedded.getRGB(x, y))
        }
        val rendered = ImageIO.read(png.toFile())
        for (y in 0 until rendered.height) for (x in 0 until rendered.width) {
            assertEquals(
                reference.getRGB(x, y),
                rendered.getRGB(x, y),
                "SVG settings must not affect later PNG rendering",
            )
        }
    }

    private fun embeddedPng(target: Path): BufferedImage = embeddedPngs(target).first()

    private fun embeddedPngs(target: Path): List<BufferedImage> =
        svgImages(target).map {
            val encoded =
                it.getAttributeNS("http://www.w3.org/1999/xlink", "href").substringAfter("base64,")
            ImageIO.read(Base64.getMimeDecoder().decode(encoded).inputStream())
        }

    private fun svgImages(target: Path): List<org.w3c.dom.Element> {
        val images =
            DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(target.toFile())
                .getElementsByTagNameNS("http://www.w3.org/2000/svg", "image")
        return (0 until images.length).map { images.item(it) as org.w3c.dom.Element }
    }

    @Test
    fun svgAndPngCropsMatchOriginalForRotationsAndOffsetCropBoxes() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val document =
                quadrantPdf(rotation).apply {
                    // Common PDF generators declare a page group even for opaque vector artwork.
                    getPage(0)
                        .cosObject
                        .setItem(
                            COSName.GROUP,
                            COSDictionary().apply {
                                setItem(COSName.TYPE, COSName.GROUP)
                                setItem(COSName.S, COSName.TRANSPARENCY)
                                setItem(COSName.CS, COSName.DEVICERGB)
                            },
                        )
                }
            val reference = PDFRenderer(document).renderImageWithDPI(0, 72f)
            val region =
                PdfFigureRegion(
                    1,
                    0.0,
                    0.0,
                    0.75,
                    0.75,
                    reference.width.toDouble(),
                    reference.height.toDouble(),
                )
            PdfFigureRenderer(document).use { renderer ->
                val png = directory.resolve("$rotation.png")
                val svg = directory.resolve("$rotation.svg")
                renderer.renderPng(region, png, 72, 0.0)
                renderer.renderSvg(region, svg, 0.0, 300)
                val xml = Files.readString(svg)
                assertTrue(xml.contains("viewBox="))
                assertFalse(xml.contains("data:image"), "Vector-only PDF must remain vector-only")
                val pngImage = ImageIO.read(png.toFile())
                val svgImage =
                    ByteArrayOutputStream().use { bytes ->
                        PNGTranscoder()
                            .transcode(
                                TranscoderInput(svg.toUri().toString()),
                                TranscoderOutput(bytes),
                            )
                        ImageIO.read(bytes.toByteArray().inputStream())
                    }
                assertEquals(pngImage.width, svgImage.width)
                assertEquals(pngImage.height, svgImage.height)
                for (x in listOf(10, pngImage.width - 10)) for (y in
                    listOf(10, pngImage.height - 10)) {
                    assertEquals(
                        reference.getRGB(x, y),
                        pngImage.getRGB(x, y),
                        "PNG crop rotation=$rotation at $x/$y",
                    )
                    assertEquals(
                        reference.getRGB(x, y),
                        svgImage.getRGB(x, y),
                        "SVG crop rotation=$rotation at $x/$y",
                    )
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
            for (region in
                listOf(
                    PdfFigureRegion(0, 0.0, 0.0, 1.0, 1.0),
                    PdfFigureRegion(1, Double.NaN, 0.0, 1.0, 1.0),
                    PdfFigureRegion(1, 0.8, 0.2, 0.1, 0.9),
                    PdfFigureRegion(1, 0.0, 0.0, 500.0, 900.0),
                    PdfFigureRegion(1, 0.0, 0.0, 1.0, 1.0, 100.0, 200.0),
                )) {
                assertFailsWith<IOException> {
                    renderer.renderSvg(region, directory.resolve("bad.svg"), 0.0, 300)
                }
            }
        }
    }

    @Test fun axialGradientKeepsColorsInSvg() = assertGradient { setFunction(redToBlue()) }

    @Test
    fun stitchedGradientKeepsColorsInSvg() = assertGradient {
        setFunction(
            PDFunctionType3(
                COSDictionary().apply {
                    setInt(COSName.FUNCTION_TYPE, 3)
                    setItem(COSName.DOMAIN, numbers(0f, 1f))
                    setItem(
                        COSName.FUNCTIONS,
                        COSArray().apply {
                            add(redToBlue().cosObject)
                            add(redToBlue().cosObject)
                        },
                    )
                    setItem(COSName.BOUNDS, numbers(0.4f))
                    setItem(COSName.ENCODE, numbers(0f, 1f, 1f, 0f))
                }
            )
        )
    }

    /** Word/Office exports: ICC-based RGB with a sampled (type 0) function. */
    @Test
    fun iccGradientWithSampledFunctionKeepsColorsInSvg() = assertGradient {
        val profile =
            COSStream().apply {
                setInt(COSName.N, 3)
                createOutputStream().use {
                    it.write(ICC_Profile.getInstance(ColorSpace.CS_sRGB).data)
                }
            }
        colorSpace =
            PDICCBased.create(
                COSArray().apply {
                    add(COSName.ICCBASED)
                    add(profile)
                },
                null,
            )
        val samples =
            COSStream().apply {
                setInt(COSName.FUNCTION_TYPE, 0)
                setItem(COSName.DOMAIN, numbers(0f, 1f))
                setItem(COSName.RANGE, numbers(0f, 1f, 0f, 1f, 0f, 1f))
                setItem(COSName.SIZE, COSArray().apply { add(COSInteger.get(3)) })
                setInt(COSName.BITS_PER_SAMPLE, 8)
                createOutputStream().use { it.write(byteArrayOf(-1, 0, 0, 0, -1, 0, 0, 0, -1)) }
            }
        setFunction(PDFunction.create(samples))
    }

    private fun numbers(vararg values: Float) =
        COSArray().apply { values.forEach { add(COSFloat(it)) } }

    private fun redToBlue() =
        PDFunctionType2(
            COSDictionary().apply {
                setInt(COSName.FUNCTION_TYPE, 2)
                setItem(COSName.DOMAIN, numbers(0f, 1f))
                setItem(COSName.C0, numbers(1f, 0f, 0f))
                setItem(COSName.C1, numbers(0f, 0f, 1f))
                setFloat(COSName.N, 1f)
            }
        )

    private fun assertGradient(configure: PDShadingType2.() -> Unit) {
        val document = PDDocument()
        val page = PDPage(PDRectangle(200f, 100f))
        document.addPage(page)
        val shading =
            PDShadingType2(COSDictionary()).apply {
                shadingType = 2
                colorSpace = PDDeviceRGB.INSTANCE
                coords = numbers(0f, 0f, 200f, 0f)
                extend =
                    COSArray().apply {
                        add(COSBoolean.TRUE)
                        add(COSBoolean.TRUE)
                    }
                configure()
            }
        PDPageContentStream(document, page).use { it.shadingFill(shading) }
        val reference = PDFRenderer(document).renderImageWithDPI(0, 72f)
        val svg = directory.resolve("gradient.svg")
        PdfFigureRenderer(document).use {
            assertEquals(0, it.renderSvg(PdfFigureRegion(1, 0.0, 0.0, 1.0, 1.0), svg, 0.0, 300))
        }
        val xml = Files.readString(svg)
        assertTrue(xml.contains("<linearGradient"))
        assertFalse(xml.contains("data:image"), "Gradient must stay vector")
        val actual = transcode(svg)
        for (x in listOf(20, 100, 180)) {
            assertColor(Color(reference.getRGB(x, 50)), Color(actual.getRGB(x, 50)), "x=$x")
        }
    }

    @Test
    fun tilingPatternRasterizesOnlyItsFillAtRequestedDpi() {
        val document = PDDocument()
        val page = PDPage(PDRectangle(200f, 100f)).apply { resources = PDResources() }
        document.addPage(page)
        val pattern =
            PDTilingPattern().apply {
                paintType = PDTilingPattern.PAINT_COLORED
                tilingType = PDTilingPattern.TILING_CONSTANT_SPACING
                bBox = PDRectangle(0f, 0f, 10f, 10f)
                xStep = 10f
                yStep = 10f
                resources = PDResources()
            }
        PDPatternContentStream(pattern).use {
            it.setNonStrokingColor(Color.RED)
            it.addRect(0f, 0f, 5f, 10f)
            it.fill()
            it.setNonStrokingColor(Color.BLUE)
            it.addRect(5f, 0f, 5f, 10f)
            it.fill()
        }
        val patternName = page.resources.add(pattern)
        PDPageContentStream(document, page).use {
            it.setNonStrokingColor(PDColor(patternName, PDPattern(null)))
            it.addRect(0f, 0f, 100f, 100f)
            it.fill()
            it.setNonStrokingColor(Color.GREEN)
            it.addRect(100f, 0f, 100f, 100f)
            it.fill()
        }
        val reference = PDFRenderer(document).renderImageWithDPI(0, 72f)
        DocumentImageWriter(
                directory.resolve("pattern.md"),
                DocumentImageOptions(),
                { PdfFigureRenderer(document) },
            )
            .use { writer ->
                val result =
                    writer.write(1, 1, PdfFigureRegion(1, 0.0, 0.0, 1.0, 1.0)) {
                        error("Provider image not needed")
                    }
                assertTrue(result!!.endsWith(".svg"))
                assertTrue(writer.warnings.isEmpty(), writer.warnings.toString())
                assertTrue(writer.report.diagnostics.single().contains("1 pattern or shading"))
                val svg = Path.of(writer.imageFiles.single())
                val images = embeddedPngs(svg)
                assertEquals(1, images.size, "Only the pattern fill may be rasterized")
                assertEquals(417, images.single().width, "100 pt at 300 DPI")
                val actual = transcode(svg)
                for (x in listOf(2, 7, 52, 57, 150)) {
                    assertColor(Color(reference.getRGB(x, 50)), Color(actual.getRGB(x, 50)), "x=$x")
                }
            }
    }

    @Test
    fun nearestNeighbourScalingIsDecidedAtSeventyTwoDpi() {
        fun checkerboard(width: Int, height: Int) =
            BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
                for (y in 0 until height) for (x in 0 until width) {
                    setRGB(x, y, if ((x + y) % 2 == 0) Color.BLACK.rgb else Color.WHITE.rgb)
                }
            }
        val document = PDDocument().apply { addPage(PDPage(PDRectangle(200f, 100f))) }
        PDPageContentStream(document, document.getPage(0)).use {
            // A barcode-like image stays crisp; a scan denser than 72 DPI stays smooth.
            it.drawImage(
                LosslessFactory.createFromImage(document, checkerboard(20, 10)),
                0f,
                0f,
                100f,
                50f,
            )
            it.drawImage(
                LosslessFactory.createFromImage(document, checkerboard(300, 150)),
                100f,
                0f,
                100f,
                50f,
            )
        }
        val svg = directory.resolve("interpolation.svg")
        PdfFigureRenderer(document).use {
            it.renderSvg(PdfFigureRegion(1, 0.0, 0.0, 1.0, 1.0), svg, 0.0, 300)
        }
        val renderings =
            svgImages(svg).associate { image ->
                val width = image.getAttribute("width").toInt()
                var node: org.w3c.dom.Node? = image
                var rendering = ""
                while (node is org.w3c.dom.Element && rendering.isEmpty()) {
                    rendering = node.getAttribute("image-rendering")
                    node = node.parentNode
                }
                width to rendering
            }
        assertEquals("optimizeSpeed", renderings[20])
        assertTrue(renderings[300] != "optimizeSpeed", renderings.toString())
    }

    private fun transcode(svg: Path): BufferedImage =
        ByteArrayOutputStream().use { bytes ->
            PNGTranscoder()
                .transcode(TranscoderInput(svg.toUri().toString()), TranscoderOutput(bytes))
            ImageIO.read(bytes.toByteArray().inputStream())
        }

    private fun assertColor(expected: Color, actual: Color, label: String) {
        assertTrue(
            kotlin.math.abs(expected.red - actual.red) < 4 &&
                kotlin.math.abs(expected.green - actual.green) < 4 &&
                kotlin.math.abs(expected.blue - actual.blue) < 4,
            "Color mismatch at $label: $expected / $actual",
        )
    }

    @Test
    fun complexTransparencyFallsBackToPngAndReportsReason() {
        val document = quadrantPdf(0)
        val page = document.getPage(0)
        page.resources = page.resources ?: PDResources()
        page.resources.add(PDExtendedGraphicsState().apply { blendMode = BlendMode.MULTIPLY })
        DocumentImageWriter(
                directory.resolve("doc.md"),
                DocumentImageOptions(),
                { PdfFigureRenderer(document) },
            )
            .use { writer ->
                val result =
                    writer.write(1, 1, PdfFigureRegion(1, 0.0, 0.0, 1.0, 1.0)) {
                        error("Provider image not needed")
                    }
                assertTrue(result!!.endsWith(".png"))
                assertTrue(writer.warnings.single().contains("trying PNG"))
                assertTrue(
                    writer.report.diagnostics.any {
                        it.contains("java.io.IOException: PDF transparency")
                    }
                )
                assertEquals(1, writer.report.succeeded)
                assertEquals(1, writer.report.png)
                assertEquals(0, writer.report.failed)
                assertTrue(Files.isRegularFile(Path.of(writer.imageFiles.single())))
            }
        Files.list(directory).use {
            assertEquals(0L, it.count(), "Uncommitted images must be cleaned")
        }
    }

    @Test
    fun missingCoordinatesFallBackAndProviderModeNeverOpensPdf() {
        for (format in listOf(DocumentImageFormat.SVG, DocumentImageFormat.PROVIDER)) {
            DocumentImageWriter(
                    directory.resolve("$format.md"),
                    DocumentImageOptions(format),
                    {
                        if (format == DocumentImageFormat.PROVIDER)
                            error("Provider mode must not open PDF")
                        PdfFigureRenderer(quadrantPdf(0))
                    },
                )
                .use { writer ->
                    assertTrue(
                        writer
                            .write(7, 2, null) {
                                ProviderDocumentImage(byteArrayOf(1, 2, 3), "jpeg")
                            }!!
                            .endsWith(".jpeg")
                    )
                    assertEquals(format == DocumentImageFormat.SVG, writer.warnings.isNotEmpty())
                }
        }
    }

    @Test
    fun reportCountsFinalOutcomesOnceEvenWhenSeveralAttemptsFail() {
        val document = quadrantPdf(0)
        document.addPage(
            PDPage(PDRectangle(200f, 100f)).apply {
                resources =
                    PDResources().apply {
                        add(PDExtendedGraphicsState().apply { blendMode = BlendMode.MULTIPLY })
                    }
            }
        )
        DocumentImageWriter(
                directory.resolve("mixed.md"),
                DocumentImageOptions(),
                { PdfFigureRenderer(document) },
            )
            .use { writer ->
                writer.write(1, 1, PdfFigureRegion(1, 0.0, 0.0, 1.0, 1.0)) { error("Not needed") }
                writer.write(2, 1, PdfFigureRegion(2, 0.0, 0.0, 1.0, 1.0)) { error("Not needed") }
                writer.write(2, 2, PdfFigureRegion(2, 0.0, 0.0, -1.0, 1.0)) {
                    ProviderDocumentImage(byteArrayOf(1), "png")
                }
                writer.write(2, 3, null) { null }
                writer.write(2, 4, null) { throw IOException("Provider download failed") }
                writer.write(1, 2, PdfFigureRegion(1, 0.0, 0.0, 1.0, 1.0)) { error("Not needed") }
                val report = writer.report
                assertEquals(6, report.total)
                assertEquals(4, report.succeeded)
                assertEquals(2, report.svg)
                assertEquals(1, report.png)
                assertEquals(1, report.provider)
                assertEquals(2, report.failed)
                assertEquals(4, writer.imageFiles.size)
                assertTrue(report.diagnostics.any { it.contains("Provider download failed") })
                assertTrue(report.diagnostics.any { it.contains("Invalid figure coordinates") })
            }
    }

    @Test
    fun providerCancellationIsNotReportedAsAnImageFailure() {
        DocumentImageWriter(
                directory.resolve("cancelled.md"),
                DocumentImageOptions(DocumentImageFormat.PROVIDER),
            )
            .use { writer ->
                assertFailsWith<java.util.concurrent.CancellationException> {
                    writer.write(1, 1, null) {
                        throw java.util.concurrent.CancellationException("Stop")
                    }
                }
                assertEquals(0, writer.report.total)
                assertTrue(writer.warnings.isEmpty())
            }
    }

    private fun quadrantPdf(rotation: Int): PDDocument {
        val document = PDDocument()
        val page =
            PDPage(PDRectangle(400f, 400f)).apply {
                cropBox = PDRectangle(50f, 75f, 200f, 100f)
                this.rotation = rotation
            }
        document.addPage(page)
        PDPageContentStream(document, page).use { content ->
            for ((index, color) in
                listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).withIndex()) {
                content.setNonStrokingColor(color)
                content.addRect(
                    50f + (index % 2) * 100f,
                    75f + (if (index < 2) 50f else 0f),
                    100f,
                    50f,
                )
                content.fill()
            }
        }
        return document
    }
}
