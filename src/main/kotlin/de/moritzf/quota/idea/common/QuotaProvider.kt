package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.settings.QuotaSettingsState
import java.util.concurrent.atomic.AtomicReference

/**
 * Common contract for quota data providers.
 */
interface QuotaProvider {
    val type: QuotaProviderType
    fun refresh()
    fun clearData(error: String? = null)
    fun hydrateFromCache(settings: QuotaSettingsState) {}
    fun persistToCache(settings: QuotaSettingsState) {}
}
