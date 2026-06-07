package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.zai.ZaiApiKeyStore
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.zai.ZaiQuota
import de.moritzf.quota.zai.ZaiQuotaClient
import de.moritzf.quota.zai.ZaiQuotaException
import de.moritzf.quota.shared.JsonSupport

/**
 * Fetches and caches Z.ai quota data.
 */
class ZaiQuotaProvider(
    private val zaiClient: ZaiQuotaClient = ZaiQuotaClient(),
    private val apiKeyProvider: () -> String? = { ZaiApiKeyStore.getInstance().loadBlocking() },
) : CachedQuotaProvider<ZaiQuota>() {
    override val type = QuotaProviderType.ZAI

    override fun currentUsageFraction(): Double? = lastQuotaRef.get()?.usageFraction()
    override fun getLastRawJson(): String? {
        lastRawJsonRef.get()?.let { return it }
        val quota = lastQuotaRef.get() ?: return null
        return runCatching { JsonSupport.json.encodeToString(ZaiQuota.serializer(), quota) }.getOrNull()
    }

    override fun refresh() {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            clearData("No Z.ai API key configured")
            return
        }

        try {
            val quota = zaiClient.fetchQuota(apiKey)
            storeQuota(quota, quota.rawJson)
        } catch (exception: ZaiQuotaException) {
            storeError(exception.message ?: "Usage request failed (HTTP ${exception.statusCode}). Try again later.", exception.responseBody)
        } catch (exception: Exception) {
            storeError(exception.message ?: "Usage request failed. Check your connection.")
        }
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

    private fun ZaiQuota.usageFraction(): Double? {
        val windows = listOfNotNull(
            sessionUsage?.usagePercent,
            weeklyUsage?.usagePercent,
            webSearchUsage?.usagePercent,
        )
        return windows.maxOrNull()?.let { it / 100.0 }
    }
}
