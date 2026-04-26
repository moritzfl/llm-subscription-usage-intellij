package de.moritzf.quota.idea.ui.indicator

import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.opencode.OpenCodeQuota

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
}
