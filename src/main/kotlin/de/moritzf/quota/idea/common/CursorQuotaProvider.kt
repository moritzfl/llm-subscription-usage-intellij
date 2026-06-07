package de.moritzf.quota.idea.common

import de.moritzf.quota.cursor.CursorAuth
import de.moritzf.quota.cursor.CursorQuota
import de.moritzf.quota.cursor.CursorQuotaClient
import de.moritzf.quota.cursor.CursorQuotaException
import de.moritzf.quota.idea.cursor.CursorCredentialsStore
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.shared.JsonSupport
import java.util.concurrent.atomic.AtomicReference

/**
 * Fetches and caches Cursor subscription usage data.
 */
class CursorQuotaProvider(
    private val cursorClient: CursorQuotaClient = CursorQuotaClient(),
    private val credentialsProvider: () -> CursorAuth? = { CursorCredentialsStore.getInstance().loadBlocking() },
) : QuotaProvider {
    override val type = QuotaProviderType.CURSOR

    private val lastQuotaRef = AtomicReference<CursorQuota?>()
    private val lastErrorRef = AtomicReference<String?>()
    private val lastRawJsonRef = AtomicReference<String?>()

    fun getLastQuota(): CursorQuota? = lastQuotaRef.get()
    fun getLastError(): String? = lastErrorRef.get()
    override fun currentUsageFraction(): Double? = lastQuotaRef.get()?.primaryUsagePercent()?.let { it / 100.0 }
    override fun getLastRawJson(): String? {
        lastRawJsonRef.get()?.let { return it }
        val quota = lastQuotaRef.get() ?: return null
        return runCatching { JsonSupport.json.encodeToString(CursorQuota.serializer(), quota) }.getOrNull()
    }

    override fun refresh() {
        val auth = credentialsProvider()
        if (auth == null || auth.accessToken.isBlank()) {
            clearData("No Cursor session cookie configured. Paste WorkosCursorSessionToken from cursor.com in settings.")
            return
        }

        try {
            val quota = cursorClient.fetchQuota(auth.accessToken, auth)
            lastQuotaRef.set(quota)
            lastErrorRef.set(null)
            lastRawJsonRef.set(quota.rawJson)
        } catch (exception: CursorQuotaException) {
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
        val cached = QuotaSnapshotCache.decodeCursorQuota(settings.cachedCursorQuotaJson)
        lastQuotaRef.set(cached)
        lastRawJsonRef.set(cached?.rawJson)
    }

    override fun persistToCache(settings: QuotaSettingsState) {
        val quota = lastQuotaRef.get()
        if (quota != null) {
            QuotaSnapshotCache.encodeCursorQuota(quota)?.let { settings.cachedCursorQuotaJson = it }
            settings.updateTimestamp(type)
        }
    }
}
