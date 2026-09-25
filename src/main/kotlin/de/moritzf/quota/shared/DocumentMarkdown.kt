package de.moritzf.quota.shared

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

internal object DocumentMarkdown {
    private val FENCE = Regex("^```(?:markdown|md)?\\s*\\n([\\s\\S]*?)\\n```\\s*$", RegexOption.IGNORE_CASE)

    fun unwrap(text: String): String {
        val trimmed = text.trim()
        return FENCE.matchEntire(trimmed)?.groupValues?.get(1) ?: trimmed
    }

    fun defaultOutput(localFile: Path?): Path? {
        if (localFile == null) return null
        val name = localFile.fileName.toString()
        val stem = name.substringBeforeLast('.', name).ifBlank { name }
        return localFile.resolveSibling("$stem.md")
    }

    fun resultJson(
        markdown: String,
        outputFile: Path?,
        imageFiles: List<String> = emptyList(),
        pageCount: Int? = null,
        pageFrom: Int? = null,
        pageTo: Int? = null,
        warnings: List<String> = emptyList(),
    ): String {
        val cleaned = unwrap(markdown)
        if (outputFile != null) {
            val parent = outputFile.parent
            if (parent != null) {
                Files.createDirectories(parent)
            }
            writeAtomically(outputFile, cleaned)
            return JsonSupport.json.encodeToString(
                DocumentMarkdownWriteResult(outputFile.toString(), imageFiles, pageCount, pageFrom, pageTo, warnings),
            )
        }
        return JsonSupport.json.encodeToString(DocumentMarkdownTextResult(cleaned, pageCount, pageFrom, pageTo))
    }

    fun writeAtomically(outputFile: Path, markdown: String) {
        val parent = outputFile.toAbsolutePath().parent
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".document-", ".md")
        try {
            Files.writeString(temporary, markdown)
            try { Files.move(temporary, outputFile, ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, outputFile, REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }
}

@Serializable
internal data class DocumentMarkdownWriteResult(
    @SerialName("output_file") val outputFile: String,
    @SerialName("image_files") val imageFiles: List<String> = emptyList(),
    @SerialName("page_count") val pageCount: Int? = null,
    @SerialName("page_from") val pageFrom: Int? = null,
    @SerialName("page_to") val pageTo: Int? = null,
    val warnings: List<String> = emptyList(),
)

@Serializable
internal data class DocumentMarkdownTextResult(
    val markdown: String,
    @SerialName("page_count") val pageCount: Int? = null,
    @SerialName("page_from") val pageFrom: Int? = null,
    @SerialName("page_to") val pageTo: Int? = null,
)
