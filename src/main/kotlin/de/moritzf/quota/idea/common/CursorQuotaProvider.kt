package de.moritzf.quota.idea.common

import de.moritzf.quota.cursor.CursorAuth
import de.moritzf.quota.cursor.CursorQuota
import de.moritzf.quota.cursor.CursorQuotaClient
import de.moritzf.quota.cursor.CursorQuotaException
import de.moritzf.quota.idea.cursor.CursorCredentialsStore

/**
 * Fetches and caches Cursor subscription usage data.
 */
class CursorQuotaProvider(
    private val cursorClient: CursorQuotaClient = CursorQuotaClient(),
    private val credentialsProvider: () -> CursorAuth? = { CursorCredentialsStore.getInstance().loadBlocking() },
) : CachedQuotaProvider<CursorQuota>() {
    override val type = QuotaProviderType.CURSOR
    override val notConfiguredMessage = "No Cursor session cookie configured. Paste WorkosCursorSessionToken from cursor.com in settings."

    override fun refresh() {
        val auth = credentialsProvider()
        if (auth == null || auth.accessToken.isBlank()) {
            clearData(notConfiguredMessage)
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
}
