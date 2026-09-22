package de.moritzf.quota.idea.ui.indicator

import de.moritzf.quota.idea.common.QuotaProviderType

enum class QuotaIndicatorSource(
    private val displayName: String,
    val providerType: QuotaProviderType? = null,
) {
    ANTIGRAVITY("Antigravity", QuotaProviderType.ANTIGRAVITY),
    AZURE("Azure", QuotaProviderType.AZURE),
    CLAUDE("Claude", QuotaProviderType.CLAUDE),
    CURSOR("Cursor", QuotaProviderType.CURSOR),
    GITHUB("GitHub Copilot", QuotaProviderType.GITHUB),
    KIMI("Kimi", QuotaProviderType.KIMI),
    MINIMAX("MiniMax", QuotaProviderType.MINIMAX),
    MISTRAL("Mistral", QuotaProviderType.MISTRAL),
    OPEN_AI("OpenAI", QuotaProviderType.OPEN_AI),
    OPEN_CODE("OpenCode", QuotaProviderType.OPEN_CODE),
    OLLAMA("Ollama", QuotaProviderType.OLLAMA),
    SUPERGROK("SuperGrok", QuotaProviderType.SUPERGROK),
    ZAI("Z.ai", QuotaProviderType.ZAI),
    LAST_USED("Last used");

    val storageId: String
        get() = providerType?.id ?: LAST_USED_ID

    override fun toString(): String = displayName

    companion object {
        const val LAST_USED_ID: String = "last_used"

        fun forProvider(type: QuotaProviderType): QuotaIndicatorSource =
            entries.first { it.providerType == type }

        @JvmStatic
        fun fromStorageValue(value: String?): QuotaIndicatorSource {
            if (value.isNullOrBlank()) return OPEN_AI
            val trimmed = value.trim()
            val key = trimmed.lowercase()
            return entries.firstOrNull { it.storageId == key }
                ?: entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
                ?: OPEN_AI
        }
    }
}
