package de.moritzf.quota.openai.proxy.pdf

import de.moritzf.quota.shared.DocumentConversionProgress
import de.moritzf.quota.shared.DocumentMarkdown
import de.moritzf.quota.shared.DocumentModels
import java.io.StringWriter
import java.nio.file.Path
import org.apache.pdfbox.Loader
import org.apache.pdfbox.tools.PDFText2Markdown

/**
 * Local PDF text extraction via Apache PDFBox [PDFText2Markdown]
 * (the `export:text -md` converter, since 3.0.4). Not OCR.
 */
internal object PdfBoxMarkdown {
    const val IMAGES_IGNORED = "PDFBox does not export figures. includeImages was ignored."

    const val NO_EMBEDDED_TEXT =
        "No embedded text was found. This PDF is probably a scan; PDFBox cannot OCR it."

    fun convert(
        source: Path,
        outputFile: Path?,
        includeImages: Boolean = false,
        pageFrom: Int? = null,
        pageTo: Int? = null,
        progress: DocumentConversionProgress = DocumentConversionProgress.NONE,
    ): String {
        require(PdfPages.isPdf(source)) { "PDFBox only extracts embedded text from a local PDF." }
        val destination = outputFile ?: DocumentMarkdown.defaultOutput(source)
            ?: error("PDFBox needs a local PDF.")
        Loader.loadPDF(source.toFile()).use { document ->
            if (!document.currentAccessPermission.canExtractContent()) {
                throw IllegalStateException("This PDF does not allow text extraction.")
            }
            val pageCount = document.numberOfPages
            val range = PdfPages.resolve(pageCount, pageFrom, pageTo)
                ?: throw IllegalArgumentException("Page range is outside this PDF ($pageCount pages).")
            val total = range.to - range.from + 1
            val writer = StringWriter()
            val stripper = PDFText2Markdown()
            for (page in range.from..range.to) {
                progress.update(page - range.from, total, "Extracting embedded text, page $page")
                stripper.startPage = page
                stripper.endPage = page
                stripper.writeText(document, writer)
            }
            progress.update(total, total, "Writing Markdown")
            val markdown = writer.toString()
            val warnings = buildList {
                add(DocumentModels.PDFBOX_WARNING)
                if (includeImages) add(IMAGES_IGNORED)
                if (markdown.isBlank()) add(NO_EMBEDDED_TEXT)
            }
            return DocumentMarkdown.resultJson(
                markdown = markdown,
                outputFile = destination,
                pageCount = pageCount,
                pageFrom = range.from,
                pageTo = range.to,
                warnings = warnings,
            )
        }
    }
}
