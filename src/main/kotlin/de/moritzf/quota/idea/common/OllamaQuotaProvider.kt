package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.ollama.OllamaApiKeyStore
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.ollama.OllamaQuota
import de.moritzf.quota.ollama.OllamaQuotaClient
import de.moritzf.quota.ollama.OllamaQuotaException
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Fetches and caches Ollama Cloud quota data via the official usage API.
 */
class OllamaQuotaProvider(
    override val accountId: String = QuotaProviderType.OLLAMA.id,
    private val ollamaClient: OllamaQuotaClient = OllamaQuotaClient(),
    private val apiKeyProvider: () -> String? = { OllamaApiKeyStore.forAccount(accountId).loadBlocking() },
    private val monthlyResetAnchorProvider: () -> Instant? = { null },
    private val nowProvider: () -> Instant = { Clock.System.now() },
) : CachedQuotaProvider<OllamaQuota>() {
    override val type = QuotaProviderType.OLLAMA
    override val notConfiguredMessage = "No Ollama API key configured"

    override fun hydrateFromCache(settings: QuotaSettingsState) {
        super.hydrateFromCache(settings)
        applyConfiguredMonthlyReset()
    }

    override fun refresh() {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            clearData(notConfiguredMessage)
            return
        }

        try {
            val quota = applyConfiguredMonthlyReset(ollamaClient.fetchQuota(apiKey))
            storeQuota(quota, quota.rawJson)
        } catch (exception: OllamaQuotaException) {
            storeFetchFailure(
                exception.statusCode,
                exception.message ?: "Request failed (${exception.statusCode})",
                exception.rawBody,
            )
        } catch (exception: Exception) {
            storeError(exception.message ?: "Request failed")
        }
    }

    private fun applyConfiguredMonthlyReset() {
        val quota = lastQuotaRef.get() ?: return
        val adjusted = applyConfiguredMonthlyReset(quota)
        if (adjusted !== quota) {
            lastQuotaRef.set(adjusted)
            lastRawJsonRef.set(adjusted.rawJson)
        }
    }

    private fun applyConfiguredMonthlyReset(quota: OllamaQuota): OllamaQuota =
        OllamaQuotaClient.applyConfiguredMonthlyReset(quota, monthlyResetAnchorProvider(), nowProvider())
}
