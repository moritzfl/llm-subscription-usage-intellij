package de.moritzf.quota.shared.document

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage

class PdfPagesTest {
    @Test
    fun resolveAndSliceKeepRequestedPages() {
        val dir = Files.createTempDirectory("pdf-pages")
        val pdf = dir.resolve("three.pdf")
        PDDocument().use { doc ->
            repeat(3) { doc.addPage(PDPage()) }
            doc.save(pdf.toFile())
        }
        assertEquals(3, PdfPages.pageCount(pdf))
        assertEquals(PdfPages.Range(1, 3, 3), PdfPages.resolve(3, null, null))
        assertEquals(PdfPages.Range(2, 2, 3), PdfPages.resolve(3, 2, 2))
        assertNull(PdfPages.resolve(3, 0, 2))
        assertNull(PdfPages.resolve(3, 2, 4))
        val slice = dir.resolve("p2.pdf")
        assertTrue(PdfPages.writeSlice(pdf, 2, 2, slice))
        Loader.loadPDF(slice.toFile()).use { doc -> assertEquals(1, doc.numberOfPages) }
        assertFalse(PdfPages.isPdf(dir.resolve("missing.pdf")))
    }
}
