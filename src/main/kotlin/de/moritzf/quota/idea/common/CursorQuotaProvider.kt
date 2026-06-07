package de.moritzf.quota.idea.common

import de.moritzf.quota.cursor.CursorAuth
import de.moritzf.quota.cursor.CursorQuota
import de.moritzf.quota.cursor.CursorQuotaClient
import de.moritzf.quota.cursor.CursorQuotaException
import de.moritzf.quota.idea.cursor.CursorCredentialsStore
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.shared.JsonSupport

/**
 * Fetches and caches Cursor subscription usage data.
 */
class CursorQuotaProvider(
    private val cursorClient: CursorQuotaClient = CursorQuotaClient(),
    private val credentialsProvider: () -> CursorAuth? = { CursorCredentialsStore.getInstance().loadBlocking() },
) : CachedQuotaProvider<CursorQuota>() {
    override val type = QuotaProviderType.CURSOR

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
            storeQuota(quota, quota.rawJson)
        } catch (exception: CursorQuotaException) {
            storeError(exception.message ?: "Usage request failed (HTTP ${exception.statusCode}). Try again later.", exception.responseBody)
        } catch (exception: Exception) {
            storeError(exception.message ?: "Usage request failed. Check your connection.")
        }
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
