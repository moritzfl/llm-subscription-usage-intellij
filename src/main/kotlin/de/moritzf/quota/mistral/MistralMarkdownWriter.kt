package de.moritzf.quota.mistral

import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.Base64

/** Streams OCR pages to a staging file. Only a complete conversion replaces the destination. */
internal class MistralMarkdownWriter(
    private val outputFile: Path,
    private val includeImages: Boolean,
) : AutoCloseable {
    private val parent = outputFile.toAbsolutePath().parent.also { Files.createDirectories(it) }
    private val temporary = Files.createTempFile(parent, ".ocr-", ".md")
    private val writer = Files.newBufferedWriter(temporary)
    private var imageDirectory: Path? = null
    private val imageFiles = mutableListOf<Path>()
    private var pageCount = 0
    private var committed = false

    fun append(pages: List<MistralOcrPageDto>) {
        for (page in pages) {
            val links = mutableMapOf<String, String>()
            if (includeImages) {
                page.images.orEmpty().forEachIndexed { index, image ->
                    val name = MistralOcrClient.imageFileName(image.id) ?: return@forEachIndexed
                    val encoded = image.imageBase64?.substringAfter("base64,")?.trim().orEmpty()
                    if (encoded.isEmpty()) return@forEachIndexed
                    val bytes = Base64.getDecoder().decode(encoded)
                    val directory = imageDirectory ?: Files.createTempDirectory(
                        parent, "${outputFile.fileName.toString().substringBeforeLast('.').take(60)}-images-",
                    ).also { imageDirectory = it }
                    val extension = name.substringAfterLast('.', "png").lowercase()
                        .takeIf { it.matches(Regex("[a-z0-9]{1,10}")) } ?: "png"
                    val target = directory.resolve("page-${pageCount + 1}-image-${index + 1}.$extension")
                    imageFiles.add(target)
                    Files.write(target, bytes, CREATE_NEW)
                    val relative = "${directory.fileName}/${target.fileName}"
                    val link = URI(null, null, relative, null).toASCIIString()
                        .replace("(", "%28").replace(")", "%29")
                    check(links.put(image.id, link) == null) { "OCR returned duplicate image id '${image.id}' on page ${pageCount + 1}." }
                    // Some responses use a basename in Markdown but a path in the image metadata.
                    links.putIfAbsent(name, link)
                }
            }
            if (pageCount > 0) writer.write("\n\n")
            writer.write(rewriteLinks(page.markdown, links))
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
        return MistralOcrWriteResult(outputFile.toString(), imageFiles.map(Path::toString), pageCount)
    }

    override fun close() {
        try {
            writer.close()
        } finally {
            if (!committed) {
                Files.deleteIfExists(temporary)
                imageFiles.forEach { Files.deleteIfExists(it) }
                imageDirectory?.let { Files.deleteIfExists(it) }
            }
        }
    }

    private fun rewriteLinks(markdown: String, links: Map<String, String>): String {
        if (links.isEmpty()) return markdown
        val ids = links.keys.sortedByDescending(String::length).joinToString("|", transform = Regex::escape)
        val patterns = listOf(
            Regex("(]\\(\\s*<?)($ids)(?=>?\\s*(?:[\"'][^\\r\\n]*[\"']\\s*)?\\))"),
            Regex("(?m)^(\\s{0,3}\\[[^]\\r\\n]+]:\\s*<?)($ids)(?=>?(?:\\s|$))"),
            Regex("(\\bsrc\\s*=\\s*[\"'])($ids)(?=[\"'])"),
        )
        return patterns.fold(markdown) { text, pattern ->
            pattern.replace(text) { match -> match.groupValues[1] + links.getValue(match.groupValues[2]) }
        }
    }
}
