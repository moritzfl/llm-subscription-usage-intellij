package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.shared.ProviderQuota

/**
 * Common contract for quota data providers.
 */
interface QuotaProvider {
    val type: QuotaProviderType

    /** Error shown when no credentials are configured; also the default when clearing usage data. */
    val notConfiguredMessage: String? get() = null

    fun refresh()
    fun clearData(error: String? = null)
    fun getLastQuota(): ProviderQuota? = null
    fun getLastError(): String? = null
    fun getLastRawJson(): String? = null
    fun currentUsageFraction(): Double? = null
    fun cachedUsageFraction(settings: QuotaSettingsState): Double? = null
    fun hydrateFromCache(settings: QuotaSettingsState) {}
    fun persistToCache(settings: QuotaSettingsState) {}
}
