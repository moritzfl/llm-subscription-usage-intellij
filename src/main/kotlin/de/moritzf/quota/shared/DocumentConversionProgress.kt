package de.moritzf.quota.shared

/** Called before and after each request; listeners may throw to cancel before publishing output. */
internal fun interface DocumentConversionProgress {
    fun update(completedPages: Int, totalPages: Int, detail: String)

    companion object {
        val NONE = DocumentConversionProgress { _, _, _ -> }
    }
}
