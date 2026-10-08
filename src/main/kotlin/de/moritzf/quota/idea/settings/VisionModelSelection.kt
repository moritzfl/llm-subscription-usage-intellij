package de.moritzf.quota.idea.settings

import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.shared.DocumentModels

/**
 * Vision-model selection. Unlike document models there is no provider default: every provider
 * starts on "-" so image analysis stays off until the user opts in.
 */
internal object VisionModelSelection {
    fun forAccount(type: QuotaProviderType, accountId: String, explicit: String = ""): String {
        val requested = explicit.trim()
        if (requested == DocumentModels.OFF) return DocumentModels.OFF
        if (requested.isNotEmpty()) return requested
        val saved = runCatching {
            QuotaSettingsState.getInstance()
                .account(accountId)
                ?.extra(ProviderAccount.EXTRA_VISION_MODEL)
        }
            .getOrNull()
        if (saved == DocumentModels.OFF) return DocumentModels.OFF
        return saved?.trim()?.takeIf { it.isNotEmpty() } ?: DocumentModels.OFF
    }

    /** True when a vision model is selected for this account. The MCP status uses this. */
    fun isEnabledForAccount(accountId: String): Boolean {
        val saved = runCatching {
            QuotaSettingsState.getInstance()
                .account(accountId)
                ?.extra(ProviderAccount.EXTRA_VISION_MODEL)
        }
            .getOrNull()
        return !saved.isNullOrBlank() && saved != DocumentModels.OFF
    }
}
