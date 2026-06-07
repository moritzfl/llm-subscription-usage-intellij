package de.moritzf.quota.idea.common

import java.util.concurrent.atomic.AtomicReference

/**
 * Shared in-memory state for quota providers.
 */
abstract class CachedQuotaProvider<Q : Any> : QuotaProvider {
    protected val lastQuotaRef = AtomicReference<Q?>()
    protected val lastErrorRef = AtomicReference<String?>()
    protected val lastRawJsonRef = AtomicReference<String?>()

    fun getLastQuota(): Q? = lastQuotaRef.get()

    fun getLastError(): String? = lastErrorRef.get()

    override fun getLastRawJson(): String? = lastRawJsonRef.get()

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
}
