package de.moritzf.quota.shared

import java.nio.file.Path
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts

/** One-page sample used by the settings document test. Created on the fly, never checked in. */
internal object HelloPdf {
    const val TEXT = "Hello from LLM Subscription Usage"

    fun write(path: Path) {
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 18f)
                content.newLineAtOffset(72f, 720f)
                content.showText(TEXT)
                content.endText()
            }
            document.save(path.toFile())
        }
    }
}
