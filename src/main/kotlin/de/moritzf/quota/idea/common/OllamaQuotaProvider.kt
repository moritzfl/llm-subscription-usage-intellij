package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.ollama.OllamaSessionCookieStore
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.ollama.OllamaQuota
import de.moritzf.quota.ollama.OllamaQuotaClient
import de.moritzf.quota.ollama.OllamaQuotaException
import java.util.concurrent.atomic.AtomicReference

/**
 * Fetches and caches Ollama Cloud quota data.
 */
class OllamaQuotaProvider(
    private val ollamaClient: OllamaQuotaClient = OllamaQuotaClient(),
    private val sessionCookieProvider: () -> String? = { OllamaSessionCookieStore.getInstance().loadSessionCookie() },
    private val cfClearanceProvider: () -> String? = { OllamaSessionCookieStore.getInstance().getCfClearance() },
) : QuotaProvider {
    override val id = "ollama"

    private val lastQuotaRef = AtomicReference<OllamaQuota?>()
    private val lastErrorRef = AtomicReference<String?>()

    fun getLastQuota(): OllamaQuota? = lastQuotaRef.get()
    fun getLastError(): String? = lastErrorRef.get()

    override fun refresh() {
        val sessionCookie = sessionCookieProvider()
        if (sessionCookie.isNullOrBlank()) {
            clearData("No session cookie configured")
            return
        }

        try {
            val cfClearance = cfClearanceProvider()
            val quota = ollamaClient.fetchQuota(sessionCookie, cfClearance)
            lastQuotaRef.set(quota)
            lastErrorRef.set(null)
        } catch (exception: OllamaQuotaException) {
            lastQuotaRef.set(null)
            lastErrorRef.set(exception.message ?: "Request failed (${exception.statusCode})")
        } catch (exception: Exception) {
            lastQuotaRef.set(null)
            lastErrorRef.set(exception.message ?: "Request failed")
        }
    }

    override fun clearData(error: String?) {
        lastQuotaRef.set(null)
        lastErrorRef.set(error)
    }

    override fun hydrateFromCache(settings: QuotaSettingsState) {
        val cached = QuotaSnapshotCache.decodeOllamaQuota(settings.cachedOllamaQuotaJson)
        lastQuotaRef.set(cached)
    }

    override fun persistToCache(settings: QuotaSettingsState) {
        val quota = lastQuotaRef.get()
        if (quota != null) {
            QuotaSnapshotCache.encodeOllamaQuota(quota)?.let { settings.cachedOllamaQuotaJson = it }
            settings.updateTimestamp(id)
        }
    }
}
