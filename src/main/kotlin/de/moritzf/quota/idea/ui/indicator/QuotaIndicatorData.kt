package de.moritzf.quota.idea.ui.indicator

import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.zai.ZaiQuota

internal sealed interface QuotaIndicatorData {
    val error: String?

    data class OpenAi(
        val quota: OpenAiCodexQuota?,
        override val error: String?,
    ) : QuotaIndicatorData

    data class OpenCode(
        val quota: OpenCodeQuota?,
        override val error: String?,
    ) : QuotaIndicatorData

    data class Ollama(
        val quota: de.moritzf.quota.ollama.OllamaQuota?,
        override val error: String?,
    ) : QuotaIndicatorData

    data class Zai(
        val quota: ZaiQuota?,
        override val error: String?,
    ) : QuotaIndicatorData
}
