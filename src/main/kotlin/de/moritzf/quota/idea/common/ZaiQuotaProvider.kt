package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.zai.ZaiApiKeyStore
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.zai.ZaiQuota
import de.moritzf.quota.zai.ZaiQuotaClient
import de.moritzf.quota.zai.ZaiQuotaException
import java.util.concurrent.atomic.AtomicReference

/**
 * Fetches and caches Z.ai quota data.
 */
class ZaiQuotaProvider(
    private val zaiClient: ZaiQuotaClient = ZaiQuotaClient(),
    private val apiKeyProvider: () -> String? = { ZaiApiKeyStore.getInstance().loadBlocking() },
) : QuotaProvider {
    override val type = QuotaProviderType.ZAI

    private val lastQuotaRef = AtomicReference<ZaiQuota?>()
    private val lastErrorRef = AtomicReference<String?>()
    private val lastRawJsonRef = AtomicReference<String?>()

    fun getLastQuota(): ZaiQuota? = lastQuotaRef.get()
    fun getLastError(): String? = lastErrorRef.get()
    fun getLastRawJson(): String? = lastRawJsonRef.get()

    override fun refresh() {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            clearData("No Z.ai API key configured")
            return
        }

        try {
            val quota = zaiClient.fetchQuota(apiKey)
            lastQuotaRef.set(quota)
            lastErrorRef.set(null)
            lastRawJsonRef.set(quota.rawJson)
        } catch (exception: ZaiQuotaException) {
            lastQuotaRef.set(null)
            lastErrorRef.set(exception.message ?: "Usage request failed (HTTP ${exception.statusCode}). Try again later.")
            lastRawJsonRef.set(exception.responseBody)
        } catch (exception: Exception) {
            lastQuotaRef.set(null)
            lastErrorRef.set(exception.message ?: "Usage request failed. Check your connection.")
            lastRawJsonRef.set(null)
        }
    }

    override fun clearData(error: String?) {
        lastQuotaRef.set(null)
        lastErrorRef.set(error)
        lastRawJsonRef.set(null)
    }

    override fun hydrateFromCache(settings: QuotaSettingsState) {
        val cached = QuotaSnapshotCache.decodeZaiQuota(settings.cachedZaiQuotaJson)
        lastQuotaRef.set(cached)
        lastRawJsonRef.set(cached?.rawJson)
    }

    override fun persistToCache(settings: QuotaSettingsState) {
        val quota = lastQuotaRef.get()
        if (quota != null) {
            QuotaSnapshotCache.encodeZaiQuota(quota)?.let { settings.cachedZaiQuotaJson = it }
            settings.updateTimestamp(type)
        }
    }
}
