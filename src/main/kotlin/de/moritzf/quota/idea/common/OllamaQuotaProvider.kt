package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.ollama.OllamaSessionCookieStore
import de.moritzf.quota.ollama.OllamaQuota
import de.moritzf.quota.ollama.OllamaQuotaClient
import de.moritzf.quota.ollama.OllamaQuotaException

/**
 * Fetches and caches Ollama Cloud quota data.
 */
class OllamaQuotaProvider(
    private val ollamaClient: OllamaQuotaClient = OllamaQuotaClient(),
    private val sessionCookieProvider: () -> String? = { OllamaSessionCookieStore.getInstance().loadBlocking().first },
    private val cfClearanceProvider: () -> String? = { OllamaSessionCookieStore.getInstance().getCfClearance() },
) : CachedQuotaProvider<OllamaQuota>() {
    override val type = QuotaProviderType.OLLAMA
    override val notConfiguredMessage = "No session cookie configured"

    override fun refresh() {
        val sessionCookie = sessionCookieProvider()
        if (sessionCookie.isNullOrBlank()) {
            clearData(notConfiguredMessage)
            return
        }

        try {
            val cfClearance = cfClearanceProvider()
            val quota = ollamaClient.fetchQuota(sessionCookie, cfClearance)
            storeQuota(quota, quota.rawJson)
        } catch (exception: OllamaQuotaException) {
            storeError(exception.message ?: "Request failed (${exception.statusCode})", exception.rawBody)
        } catch (exception: Exception) {
            storeError(exception.message ?: "Request failed")
        }
    }
}
