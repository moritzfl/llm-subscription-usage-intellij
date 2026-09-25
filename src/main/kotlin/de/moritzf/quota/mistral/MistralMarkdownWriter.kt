package de.moritzf.quota.mistral

import de.moritzf.quota.openai.proxy.pdf.PdfFigureRegion
import de.moritzf.quota.openai.proxy.pdf.PdfFigureRenderer
import de.moritzf.quota.shared.DocumentImageOptions
import de.moritzf.quota.shared.DocumentImageWriter
import de.moritzf.quota.shared.ProviderDocumentImage
import de.moritzf.quota.shared.rewriteMarkdownImageLinks
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Base64

/** Streams OCR pages to a staging file. Only a complete conversion replaces the destination. */
internal class MistralMarkdownWriter(
    private val outputFile: Path,
    private val includeImages: Boolean,
    imageOptions: DocumentImageOptions = DocumentImageOptions(),
    originalPdf: () -> PdfFigureRenderer? = { null },
) : AutoCloseable {
    private val parent = outputFile.toAbsolutePath().parent.also { Files.createDirectories(it) }
    private val temporary = Files.createTempFile(parent, ".ocr-", ".md")
    private val writer = Files.newBufferedWriter(temporary)
    private val images = DocumentImageWriter(outputFile, imageOptions, originalPdf)
    private var pageCount = 0
    private var committed = false

    fun append(pages: List<MistralOcrPageDto>, pageOffset: Int = pageCount) {
        for ((pageIndex, page) in pages.withIndex()) {
            val pageNumber = pageOffset + pageIndex + 1
            val links = mutableMapOf<String, String?>()
            if (includeImages) {
                page.images.orEmpty().forEachIndexed { index, image ->
                    val name = MistralOcrClient.imageFileName(image.id) ?: return@forEachIndexed
                    val box = listOfNotNull(image.topLeftX, image.topLeftY, image.bottomRightX, image.bottomRightY)
                    val region = if (page.index == null || page.index == pageIndex) {
                        PdfFigureRegion.fromPixels(pageNumber, box, page.dimensions?.width, page.dimensions?.height)
                    } else null
                    val link = images.write(pageNumber, index + 1, region) {
                        val encoded = image.imageBase64?.substringAfter("base64,")?.trim().orEmpty()
                        if (encoded.isEmpty()) null else ProviderDocumentImage(
                            Base64.getDecoder().decode(encoded), name.substringAfterLast('.', "png"),
                        )
                    }
                    check(!links.containsKey(image.id)) { "OCR returned duplicate image id '${image.id}' on page $pageNumber." }
                    links[image.id] = link
                    // Some responses use a basename in Markdown but a path in the image metadata.
                    links.putIfAbsent(name, link)
                }
            }
            if (pageCount > 0) writer.write("\n\n")
            writer.write(rewriteMarkdownImageLinks(page.markdown, links))
            pageCount++
        }
    }

    fun commit(): MistralOcrWriteResult {
        writer.close()
        try {
            Files.move(temporary, outputFile, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, outputFile, REPLACE_EXISTING)
        }
        committed = true
        images.commit()
        return MistralOcrWriteResult(outputFile.toString(), images.imageFiles, pageCount, images.warnings)
    }

    override fun close() {
        try {
            writer.close()
        } finally {
            try { if (!committed) Files.deleteIfExists(temporary) } finally { images.close() }
        }
    }
}
