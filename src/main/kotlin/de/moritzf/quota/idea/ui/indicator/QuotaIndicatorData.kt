package de.moritzf.quota.idea.ui.indicator

import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.zai.ZaiQuota
import de.moritzf.quota.minimax.MiniMaxQuota
import de.moritzf.quota.kimi.KimiQuota
import de.moritzf.quota.cursor.CursorQuota

internal sealed interface QuotaIndicatorData {
    val error: String?

    data class OpenAi(
        val quota: OpenAiCodexQuota?,
        override val error: String?,
    ) : QuotaIndicatorData

    data class MiniMax(
        val quota: MiniMaxQuota?,
        override val error: String?,
    ) : QuotaIndicatorData

    data class Kimi(
        val quota: KimiQuota?,
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

    data class Cursor(
        val quota: CursorQuota?,
        override val error: String?,
    ) : QuotaIndicatorData
}
