package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.shared.ProviderQuota
import java.util.concurrent.atomic.AtomicReference

/**
 * Shared in-memory state and cache persistence for quota providers.
 */
abstract class CachedQuotaProvider<Q : ProviderQuota> : QuotaProvider {
    protected val lastQuotaRef = AtomicReference<Q?>()
    protected val lastErrorRef = AtomicReference<String?>()
    protected val lastRawJsonRef = AtomicReference<String?>()

    override fun getLastQuota(): Q? = lastQuotaRef.get()

    override fun getLastError(): String? = lastErrorRef.get()

    override fun getLastRawJson(): String? {
        lastRawJsonRef.get()?.let { return it }
        val quota = lastQuotaRef.get() ?: return null
        return QuotaSnapshotCache.encodePlain(type, quota)
    }

    override fun currentUsageFraction(): Double? = lastQuotaRef.get()?.usageFraction()

    override fun cachedUsageFraction(settings: QuotaSettingsState): Double? = decodeCached(settings)?.usageFraction()

    override fun hydrateFromCache(settings: QuotaSettingsState) {
        val cached = decodeCached(settings)
        lastQuotaRef.set(cached)
        lastRawJsonRef.set(cached?.rawJson)
    }

    override fun persistToCache(settings: QuotaSettingsState) {
        val quota = lastQuotaRef.get() ?: return
        QuotaSnapshotCache.encode(type, quota)?.let { settings.setCachedQuotaJson(type, it) }
        settings.updateTimestamp(type)
    }

    override fun clearData(error: String?) {
        lastQuotaRef.set(null)
        lastErrorRef.set(error)
        lastRawJsonRef.set(null)
    }

    protected fun storeQuota(quota: Q, rawJson: String?) {
        lastQuotaRef.set(quota)
        lastErrorRef.set(null)
        lastRawJsonRef.set(rawJson)
    }

    protected fun storeError(error: String?, rawJson: String? = null) {
        lastQuotaRef.set(null)
        lastErrorRef.set(error)
        lastRawJsonRef.set(rawJson)
    }

    @Suppress("UNCHECKED_CAST")
    protected fun decodeCached(settings: QuotaSettingsState): Q? =
        QuotaSnapshotCache.decode(type, settings.cachedQuotaJson(type)) as? Q
}
