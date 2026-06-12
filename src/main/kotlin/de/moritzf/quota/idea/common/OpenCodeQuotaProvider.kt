package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.opencode.OpenCodeSessionCookieStore
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.opencode.OpenCodeQuotaClient
import de.moritzf.quota.opencode.OpenCodeQuotaException
import java.util.concurrent.atomic.AtomicReference

/**
 * Fetches and caches OpenCode quota data.
 */
class OpenCodeQuotaProvider(
    private val openCodeClient: OpenCodeQuotaClient = OpenCodeQuotaClient(),
    private val openCodeCookieProvider: () -> String? = { OpenCodeSessionCookieStore.getInstance().loadBlocking() },
    private val settingsProvider: () -> QuotaSettingsState? = {
        runCatching { QuotaSettingsState.getInstance() }.getOrNull()
    },
) : CachedQuotaProvider<OpenCodeQuota>() {
    override val type = QuotaProviderType.OPEN_CODE
    override val notConfiguredMessage = "No session cookie configured"

    private val lastCookieRef = AtomicReference<String?>()
    private val cachedWorkspaceId = AtomicReference<String?>()
    private val cachedWorkspaceIdTimestamp = AtomicReference(0L)

    override fun getLastRawJson(): String? {
        return lastRawJsonRef.get() ?: lastQuotaRef.get()?.rawJson
    }

    override fun refresh() {
        val cookie = openCodeCookieProvider()
        if (cookie.isNullOrBlank()) {
            clearData(notConfiguredMessage)
            return
        }

        resetOpenCodeCachesIfCookieChanged(cookie)

        try {
            val quota = fetchOpenCodeQuota(cookie)
            storeQuota(quota, quota.rawJson)
        } catch (exception: OpenCodeQuotaException) {
            if (shouldRetryOpenCode(exception)) {
                resetOpenCodeCaches()
                try {
                    val quota = fetchOpenCodeQuota(cookie)
                    storeQuota(quota, quota.rawJson)
                } catch (retryException: OpenCodeQuotaException) {
                    storeError(retryException.message ?: "Request failed (${retryException.statusCode})", retryException.rawBody)
                } catch (retryException: Exception) {
                    storeError(retryException.message ?: "Request failed")
                }
            } else {
                storeError(exception.message ?: "Request failed (${exception.statusCode})", exception.rawBody)
            }
        } catch (exception: Exception) {
            storeError(exception.message ?: "Request failed")
        }
    }

    override fun clearData(error: String?) {
        resetOpenCodeCaches()
        lastCookieRef.set(null)
        super.clearData(error)
    }

    fun resetWorkspaceCache() {
        cachedWorkspaceId.set(null)
        cachedWorkspaceIdTimestamp.set(0)
        OpenCodeQuotaClient.clearCachedFunctionId()
    }

    override fun hydrateFromCache(settings: QuotaSettingsState) {
        val cached = decodeCached(settings)
        if (cached?.rawJson == null) {
            cached?.rawJson = OpenCodeQuotaClient.buildRawResponse(cached.rawGoJson, cached.rawBillingJson)
        }
        lastQuotaRef.set(cached)
        lastRawJsonRef.set(cached?.rawJson)
    }

    private fun fetchOpenCodeQuota(sessionCookie: String): OpenCodeQuota {
        val workspaceId = resolveWorkspaceId(sessionCookie)
        return openCodeClient.fetchQuota(sessionCookie, workspaceId)
    }

    private fun resolveWorkspaceId(sessionCookie: String): String {
        val cached = cachedWorkspaceId.get()
        val timestamp = cachedWorkspaceIdTimestamp.get()
        if (cached != null && System.currentTimeMillis() - timestamp < WORKSPACE_CACHE_TTL_MS) {
            return cached
        }

        val settings = settingsProvider()
        val storedWorkspaceId = settings?.openCodeWorkspaceId
        if (!storedWorkspaceId.isNullOrBlank()) {
            cachedWorkspaceId.set(storedWorkspaceId)
            cachedWorkspaceIdTimestamp.set(System.currentTimeMillis())
            return storedWorkspaceId
        }

        val workspaceId = openCodeClient.discoverWorkspaceId(sessionCookie)
        cachedWorkspaceId.set(workspaceId)
        cachedWorkspaceIdTimestamp.set(System.currentTimeMillis())
        return workspaceId
    }

    private fun resetOpenCodeCachesIfCookieChanged(sessionCookie: String) {
        val previousCookie = lastCookieRef.getAndSet(sessionCookie)
        if (previousCookie != null && previousCookie != sessionCookie) {
            resetOpenCodeCaches()
        }
    }

    private fun resetOpenCodeCaches() {
        cachedWorkspaceId.set(null)
        cachedWorkspaceIdTimestamp.set(0)
        OpenCodeQuotaClient.clearCachedFunctionId()
    }

    private fun shouldRetryOpenCode(exception: OpenCodeQuotaException): Boolean {
        return exception.statusCode == 0 ||
            exception.statusCode == 401 ||
            exception.statusCode == 403 ||
            exception.message?.contains("Could not parse OpenCode quota response") == true
    }

    companion object {
        private const val WORKSPACE_CACHE_TTL_MS = 30 * 60 * 1000L
    }
}
