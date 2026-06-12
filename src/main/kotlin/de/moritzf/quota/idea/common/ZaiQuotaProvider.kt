package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.zai.ZaiApiKeyStore
import de.moritzf.quota.zai.ZaiQuota
import de.moritzf.quota.zai.ZaiQuotaClient
import de.moritzf.quota.zai.ZaiQuotaException

/**
 * Fetches and caches Z.ai quota data.
 */
class ZaiQuotaProvider(
    private val zaiClient: ZaiQuotaClient = ZaiQuotaClient(),
    private val apiKeyProvider: () -> String? = { ZaiApiKeyStore.getInstance().loadBlocking() },
) : CachedQuotaProvider<ZaiQuota>() {
    override val type = QuotaProviderType.ZAI
    override val notConfiguredMessage = "No Z.ai API key configured"

    override fun refresh() {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            clearData(notConfiguredMessage)
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
}
