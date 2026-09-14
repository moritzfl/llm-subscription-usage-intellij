package de.moritzf.proxy.fim

data class FimFileSlice(
    val path: String,
    val content: String,
)

data class FimContext(
    val schema: FimSchema,
    val prefix: String,
    val suffix: String,
    val filePath: String? = null,
    val repoName: String? = null,
    val languageHint: String? = null,
    val extraFiles: List<FimFileSlice> = emptyList(),
) {
    fun fingerprint(): String {
        var hash = schema.hashCode()
        hash = 31 * hash + prefix.hashCode()
        hash = 31 * hash + suffix.hashCode()
        extraFiles.forEach { slice ->
            hash = 31 * hash + slice.path.hashCode()
            hash = 31 * hash + slice.content.hashCode()
        }
        return "$hash:${prefix.length}:${suffix.length}"
    }

    fun isCommentHole(): Boolean = FimCommentContext.detect(prefix, languageHint) != null

    fun commentContinuesAfterCursor(): Boolean = CompletionSanitizer.commentContinuesAfterCursor(suffix)
}
