package de.moritzf.quota.shared

/**
 * Document-model lists. A live capability filter is used only when the provider actually reported it.
 * Otherwise every discovered model is offered so a new release does not require a plugin update.
 */
internal object DocumentModelChoices {
    /**
     * Drop a model only when that model reported capabilities and did not include PDF.
     * A sibling that reported PDF must not hide a model whose payload said nothing.
     */
    fun pdfOrAll(models: List<String>, pdfModels: Set<String>, knownModels: Set<String>): List<String> {
        val ids = models.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (knownModels.isEmpty()) return ids
        return ids.filter { it in pdfModels || it !in knownModels }
    }
}
