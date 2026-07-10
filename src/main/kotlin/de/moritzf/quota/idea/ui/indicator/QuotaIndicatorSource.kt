package de.moritzf.quota.idea.ui.indicator

import de.moritzf.quota.idea.common.QuotaProviderType

enum class QuotaIndicatorSource(
    private val displayName: String,
    val providerType: QuotaProviderType? = null,
) {
    CLAUDE("Claude", QuotaProviderType.CLAUDE),
    CURSOR("Cursor", QuotaProviderType.CURSOR),
    GITHUB("GitHub Copilot", QuotaProviderType.GITHUB),
    KIMI("Kimi", QuotaProviderType.KIMI),
    MINIMAX("MiniMax", QuotaProviderType.MINIMAX),
    OPEN_AI("OpenAI", QuotaProviderType.OPEN_AI),
    OPEN_CODE("OpenCode", QuotaProviderType.OPEN_CODE),
    OLLAMA("Ollama", QuotaProviderType.OLLAMA),
    SUPERGROK("SuperGrok", QuotaProviderType.SUPERGROK),
    ZAI("Z.ai", QuotaProviderType.ZAI),
    LAST_USED("Last used");

    override fun toString(): String = displayName

    companion object {
        @JvmStatic
        fun fromStorageValue(value: String?): QuotaIndicatorSource {
            if (value.isNullOrBlank()) return OPEN_AI
            val trimmed = value.trim()
            return entries.firstOrNull { it.name == trimmed }
                ?: QuotaProviderType.fromName(trimmed)?.let { type ->
                    entries.firstOrNull { it.providerType == type }
                } ?: OPEN_AI
        }
    }
}
