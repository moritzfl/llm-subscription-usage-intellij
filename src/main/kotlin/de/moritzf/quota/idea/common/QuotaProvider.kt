package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.settings.QuotaSettingsState

/**
 * Common contract for quota data providers.
 */
interface QuotaProvider {
    val type: QuotaProviderType
    fun refresh()
    fun clearData(error: String? = null)
    fun getLastRawJson(): String? = null
    fun currentUsageFraction(): Double? = null
    fun cachedUsageFraction(settings: QuotaSettingsState): Double? = null
    fun hydrateFromCache(settings: QuotaSettingsState) {}
    fun persistToCache(settings: QuotaSettingsState) {}
}
