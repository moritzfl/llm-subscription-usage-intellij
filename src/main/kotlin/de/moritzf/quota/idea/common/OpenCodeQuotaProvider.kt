package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.opencode.OpenCodeSessionCookieStore
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.opencode.OpenCodeQuotaClient
import de.moritzf.quota.opencode.OpenCodeQuotaException
import java.util.concurrent.atomic.AtomicReference

/**
 * Fetches and caches OpenCode Go quota data.
 */
class OpenCodeQuotaProvider(
    private val openCodeClient: OpenCodeQuotaClient = OpenCodeQuotaClient(),
    private val openCodeCookieProvider: () -> String? = { OpenCodeSessionCookieStore.getInstance().loadBlocking() },
    private val settingsProvider: () -> QuotaSettingsState? = {
        runCatching { QuotaSettingsState.getInstance() }.getOrNull()
    },
) : QuotaProvider {
    override val id = "opencode"

    private val lastQuotaRef = AtomicReference<OpenCodeQuota?>()
    private val lastErrorRef = AtomicReference<String?>()
    private val lastCookieRef = AtomicReference<String?>()
    private val cachedWorkspaceId = AtomicReference<String?>()
    private val cachedWorkspaceIdTimestamp = AtomicReference(0L)

    fun getLastQuota(): OpenCodeQuota? = lastQuotaRef.get()
    fun getLastError(): String? = lastErrorRef.get()

    override fun refresh() {
        val cookie = openCodeCookieProvider()
        if (cookie.isNullOrBlank()) {
            clearData("No session cookie configured")
            return
        }

        resetOpenCodeCachesIfCookieChanged(cookie)

        try {
            val quota = fetchOpenCodeQuota(cookie)
            lastQuotaRef.set(quota)
            lastErrorRef.set(null)
        } catch (exception: OpenCodeQuotaException) {
            if (shouldRetryOpenCode(exception)) {
                resetOpenCodeCaches()
                try {
                    val quota = fetchOpenCodeQuota(cookie)
                    lastQuotaRef.set(quota)
                    lastErrorRef.set(null)
                } catch (retryException: OpenCodeQuotaException) {
                    lastQuotaRef.set(null)
                    lastErrorRef.set(retryException.message ?: "Request failed (${retryException.statusCode})")
                } catch (retryException: Exception) {
                    lastQuotaRef.set(null)
                    lastErrorRef.set(retryException.message ?: "Request failed")
                }
            } else {
                lastQuotaRef.set(null)
                lastErrorRef.set(exception.message ?: "Request failed (${exception.statusCode})")
            }
        } catch (exception: Exception) {
            lastQuotaRef.set(null)
            lastErrorRef.set(exception.message ?: "Request failed")
        }
    }

    override fun clearData(error: String?) {
        resetOpenCodeCaches()
        lastCookieRef.set(null)
        lastQuotaRef.set(null)
        lastErrorRef.set(error)
    }

    fun resetWorkspaceCache() {
        cachedWorkspaceId.set(null)
        cachedWorkspaceIdTimestamp.set(0)
        OpenCodeQuotaClient.clearCachedFunctionId()
    }

    override fun hydrateFromCache(settings: QuotaSettingsState) {
        val cached = QuotaSnapshotCache.decodeOpenCodeQuota(settings.cachedOpenCodeQuotaJson)
        lastQuotaRef.set(cached)
    }

    override fun persistToCache(settings: QuotaSettingsState) {
        val quota = lastQuotaRef.get()
        if (quota != null) {
            QuotaSnapshotCache.encodeOpenCodeQuota(quota)?.let { settings.cachedOpenCodeQuotaJson = it }
            settings.updateTimestamp(id)
        }
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
