package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.ollama.OllamaSessionCookieStore
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.ollama.OllamaQuota
import de.moritzf.quota.ollama.OllamaQuotaClient
import de.moritzf.quota.ollama.OllamaQuotaException
import de.moritzf.quota.shared.JsonSupport
import java.util.concurrent.atomic.AtomicReference

/**
 * Fetches and caches Ollama Cloud quota data.
 */
class OllamaQuotaProvider(
    private val ollamaClient: OllamaQuotaClient = OllamaQuotaClient(),
    private val sessionCookieProvider: () -> String? = { OllamaSessionCookieStore.getInstance().loadBlocking().first },
    private val cfClearanceProvider: () -> String? = { OllamaSessionCookieStore.getInstance().getCfClearance() },
) : QuotaProvider {
    override val type = QuotaProviderType.OLLAMA

    private val lastQuotaRef = AtomicReference<OllamaQuota?>()
    private val lastErrorRef = AtomicReference<String?>()
    private val lastRawJsonRef = AtomicReference<String?>()

    fun getLastQuota(): OllamaQuota? = lastQuotaRef.get()
    fun getLastError(): String? = lastErrorRef.get()
    override fun currentUsageFraction(): Double? = lastQuotaRef.get()?.usageFraction()
    override fun getLastRawJson(): String? {
        lastRawJsonRef.get()?.let { return it }
        val quota = lastQuotaRef.get() ?: return null
        return runCatching { JsonSupport.json.encodeToString(OllamaQuota.serializer(), quota) }.getOrNull()
    }

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
            lastRawJsonRef.set(quota.rawJson)
        } catch (exception: OllamaQuotaException) {
            lastQuotaRef.set(null)
            lastErrorRef.set(exception.message ?: "Request failed (${exception.statusCode})")
            lastRawJsonRef.set(exception.rawBody)
        } catch (exception: Exception) {
            lastQuotaRef.set(null)
            lastErrorRef.set(exception.message ?: "Request failed")
            lastRawJsonRef.set(null)
        }
    }

    override fun clearData(error: String?) {
        lastQuotaRef.set(null)
        lastErrorRef.set(error)
        lastRawJsonRef.set(null)
    }

    override fun hydrateFromCache(settings: QuotaSettingsState) {
        val cached = QuotaSnapshotCache.decodeOllamaQuota(settings.cachedOllamaQuotaJson)
        lastQuotaRef.set(cached)
        lastRawJsonRef.set(cached?.rawJson)
    }

    override fun persistToCache(settings: QuotaSettingsState) {
        val quota = lastQuotaRef.get()
        if (quota != null) {
            QuotaSnapshotCache.encodeOllamaQuota(quota)?.let { settings.cachedOllamaQuotaJson = it }
            settings.updateTimestamp(type)
        }
    }

    private fun OllamaQuota.usageFraction(): Double? {
        val windows = listOfNotNull(sessionUsage?.usagePercent, weeklyUsage?.usagePercent)
        return windows.maxOrNull()?.let { it / 100.0 }
    }
}
