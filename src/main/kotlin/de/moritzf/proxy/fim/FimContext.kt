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
)
