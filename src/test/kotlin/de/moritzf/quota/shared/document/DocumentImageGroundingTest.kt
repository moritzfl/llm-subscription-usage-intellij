package de.moritzf.quota.shared.document

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentImageGroundingTest {
    @Test
    fun parsesRegionsAndStripsComments() {
        val markdown =
            """
            # Title

            ![chart](image-p1-1.png)
            <!-- img page=1 0.10 0.20 0.50 0.60 -->

            text

            ![photo](image-p3-1.png)
            <!-- img page=3 0.25 0.30 0.75 0.90 -->
            """
                .trimIndent()

        val result = DocumentImageGrounding.parse(markdown)

        assertEquals(2, result.regions.size)
        val first = result.regions[0]
        assertEquals(1, first.page)
        assertEquals(0.10f, first.x0, 1e-4f)
        assertEquals(0.20f, first.y0, 1e-4f)
        assertEquals(0.50f, first.x1, 1e-4f)
        assertEquals(0.60f, first.y1, 1e-4f)
        assertEquals("image-p1-1.png", first.fallbackFileName)
        assertEquals(3, result.regions[1].page)
        assertEquals("image-p3-1.png", result.regions[1].fallbackFileName)
        assertFalse(result.markdown.contains("<!-- img"))
        assertTrue(result.markdown.contains("![chart](image-p1-1.png)"))
        assertTrue(result.markdown.contains("![photo](image-p3-1.png)"))
        assertFalse(result.markdown.contains("\n\n\n"))
    }

    @Test
    fun remapsSlicePageNumbersOntoTheSourceDocument() {
        val parsed =
            DocumentImageGrounding.parse(
                "![chart](image-p1-1.png)\n<!-- img page=1 0.1 0.2 0.5 0.6 -->\n"
            )
        val remapped = DocumentImageGrounding.remapPageOffset(parsed, 10)
        assertEquals(11, remapped.regions.single().page)
        assertTrue(remapped.markdown.contains("![chart](image-p11-1.png)"))
        assertFalse(remapped.markdown.contains("image-p1-1.png"))
    }

    @Test
    fun rewritesPlaceholdersWhenImageFileWasNotWritten() {
        val markdown = "![a chart](image-p1-1.png)\n\n![kept](image-p2-1.png)\n"
        val rewritten =
            DocumentImageGrounding.rewriteMissingImageLinks(markdown, setOf("image-p2-1.png"))
        assertTrue(rewritten.contains("**Figure.** a chart"))
        assertFalse(rewritten.contains("](image-p1-1.png)"))
        assertTrue(rewritten.contains("![kept](image-p2-1.png)"))
    }

    @Test
    fun ignoresMalformedGroundingLines() {
        val result =
            DocumentImageGrounding.parse("![x](image-p1-1.png)\n<!-- img page=1 0.1 oops -->\n")
        assertEquals(emptyList(), result.regions)
        assertFalse(result.markdown.contains("<!-- img"))
    }

    @Test
    fun clampsOutOfRangeCoordinatesInExtractor() {
        val dir = Files.createTempDirectory("grounding-clamp")
        val pdf = dir.resolve("p.pdf")
        org.apache.pdfbox.pdmodel.PDDocument().use { doc ->
            doc.addPage(
                org.apache.pdfbox.pdmodel.PDPage(
                    org.apache.pdfbox.pdmodel.common.PDRectangle(612f, 792f)
                )
            )
            doc.save(pdf.toFile())
        }
        val region =
            de.moritzf.quota.shared.document.PdfRegionImageExtractor.PageRegion(
                page = 1,
                x0 = -0.5f,
                y0 = 0.1f,
                x1 = 1.8f,
                y1 = 0.4f,
            )
        val box = region.normalizedBox()!!
        assertEquals(0.0f, box.left, 1e-4f)
        assertEquals(0.1f, box.top, 1e-4f)
        assertEquals(1.0f, box.right, 1e-4f)
        assertEquals(0.4f, box.bottom, 1e-4f)

        val target = dir.resolve("crop.png")
        de.moritzf.quota.shared.document.PdfRegionImageExtractor.open(pdf).use { ex ->
            assertTrue(ex!!.renderRegion(region, target))
        }
        assertTrue(Files.isRegularFile(target))
        // PDF backgrounds are transparent; rendered pixels must be composited onto white, not
        // black.
        val rgb = javax.imageio.ImageIO.read(target.toFile()).getRGB(5, 5)
        val blue = rgb and 0xFF
        assertTrue(blue > 200, "expected near-white background, got rgb=$rgb")
    }

    @Test
    fun rejectsDegenerateBoxes() {
        val tiny =
            de.moritzf.quota.shared.document.PdfRegionImageExtractor.PageRegion(
                page = 1,
                x0 = 0.5f,
                y0 = 0.5f,
                x1 = 0.501f,
                y1 = 0.501f,
            )
        val outside =
            de.moritzf.quota.shared.document.PdfRegionImageExtractor.PageRegion(
                page = 1,
                x0 = 2.0f,
                y0 = 2.0f,
                x1 = 3.0f,
                y1 = 3.0f,
            )
        assertEquals(null, tiny.normalizedBox())
        assertEquals(null, outside.normalizedBox())
    }

    @Test
    fun opensNonPdfAsNull() {
        val dir = Files.createTempDirectory("grounding-nopdf")
        val notPdf = dir.resolve("x.bin")
        Files.write(notPdf, byteArrayOf(1, 2, 3))
        val ex = de.moritzf.quota.shared.document.PdfRegionImageExtractor.open(notPdf)
        assertEquals(null, ex)
    }
}
