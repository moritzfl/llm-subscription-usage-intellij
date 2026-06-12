package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.ollama.OllamaSessionCookieStore
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.ollama.OllamaQuota
import de.moritzf.quota.ollama.OllamaQuotaClient
import de.moritzf.quota.ollama.OllamaQuotaException
import de.moritzf.quota.shared.JsonSupport

/**
 * Fetches and caches Ollama Cloud quota data.
 */
class OllamaQuotaProvider(
    private val ollamaClient: OllamaQuotaClient = OllamaQuotaClient(),
    private val sessionCookieProvider: () -> String? = { OllamaSessionCookieStore.getInstance().loadBlocking().first },
    private val cfClearanceProvider: () -> String? = { OllamaSessionCookieStore.getInstance().getCfClearance() },
) : CachedQuotaProvider<OllamaQuota>() {
    override val type = QuotaProviderType.OLLAMA

    override fun currentUsageFraction(): Double? = lastQuotaRef.get()?.usageFraction()
    override fun cachedUsageFraction(settings: QuotaSettingsState): Double? {
        return QuotaSnapshotCache.decodeOllamaQuota(settings.cachedOllamaQuotaJson)?.usageFraction()
    }
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
            storeQuota(quota, quota.rawJson)
        } catch (exception: OllamaQuotaException) {
            storeError(exception.message ?: "Request failed (${exception.statusCode})", exception.rawBody)
        } catch (exception: Exception) {
            storeError(exception.message ?: "Request failed")
        }
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
}
