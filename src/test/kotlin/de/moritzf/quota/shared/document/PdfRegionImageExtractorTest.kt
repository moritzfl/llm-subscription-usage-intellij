package de.moritzf.quota.shared.document

import java.awt.Color
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle

class PdfRegionImageExtractorTest {
    @Test
    fun registersJpeg2000ReaderForPluginClassloader() {
        PdfImageIoPlugins.ensureRegistered()
        assertTrue(ImageIO.getImageReadersByFormatName("JPEG2000").hasNext())
    }

    @Test
    fun cropsTopLeftAndBottomRightInTopLeftNormalizedSpace() {
        val dir = Files.createTempDirectory("pdf-region-map")
        val pdf = dir.resolve("corners.pdf")
        val pageWidth = 612f
        val pageHeight = 792f
        val square = 72f
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle(pageWidth, pageHeight))
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                // PDF origin is bottom-left. Top-left square in page space:
                cs.setNonStrokingColor(Color.RED)
                cs.addRect(0f, pageHeight - square, square, square)
                cs.fill()
                // Bottom-right square:
                cs.setNonStrokingColor(Color.BLUE)
                cs.addRect(pageWidth - square, 0f, square, square)
                cs.fill()
            }
            doc.save(pdf.toFile())
        }

        PdfRegionImageExtractor.open(pdf).use { extractor ->
            val ex = extractor!!
            val topLeft = dir.resolve("top-left.png")
            assertTrue(
                ex.renderRegion(
                    PdfRegionImageExtractor.PageRegion(
                        1,
                        0f,
                        0f,
                        square / pageWidth,
                        square / pageHeight,
                    ),
                    topLeft,
                )
            )
            val bottomRight = dir.resolve("bottom-right.png")
            assertTrue(
                ex.renderRegion(
                    PdfRegionImageExtractor.PageRegion(
                        1,
                        (pageWidth - square) / pageWidth,
                        (pageHeight - square) / pageHeight,
                        1f,
                        1f,
                    ),
                    bottomRight,
                )
            )

            val red = ImageIO.read(topLeft.toFile())
            val blue = ImageIO.read(bottomRight.toFile())
            assertEquals(
                Color.RED.rgb,
                red.getRGB(red.width / 2, red.height / 2),
                "top-left crop should be red",
            )
            assertEquals(
                Color.BLUE.rgb,
                blue.getRGB(blue.width / 2, blue.height / 2),
                "bottom-right crop should be blue",
            )
        }
    }
}
