package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.auth.OAuthCredentials
import de.moritzf.quota.idea.opencode.OpenCodeAuthService
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.opencode.OpenCodeQuotaClient
import de.moritzf.quota.opencode.OpenCodeQuotaException
import java.util.concurrent.atomic.AtomicReference

class OpenCodeQuotaProvider(
    override val accountId: String = QuotaProviderType.OPEN_CODE.id,
    private val openCodeClient: OpenCodeQuotaClient = OpenCodeQuotaClient(),
    private val credentialsProvider: (String?) -> OAuthCredentials? = { rejected ->
        OpenCodeAuthService.getInstance().credentials(accountId, rejected)
    },
    private val settingsProvider: () -> QuotaSettingsState? = {
        runCatching { QuotaSettingsState.getInstance() }.getOrNull()
    },
) : CachedQuotaProvider<OpenCodeQuota>() {
    override val type = QuotaProviderType.OPEN_CODE
    override val notConfiguredMessage = "Not signed in to OpenCode"
    private val lastToken = AtomicReference<String?>()
    private val cachedWorkspaceId = AtomicReference<String?>()
    private val dataLock = Any()
    private var generation = 0L

    override fun getLastRawJson(): String? = lastRawJsonRef.get() ?: lastQuotaRef.get()?.rawJson

    override fun refresh() {
        val startedGeneration = synchronized(dataLock) { generation }
        fun update(action: () -> Unit) = synchronized(dataLock) {
            if (generation == startedGeneration) action()
        }
        try {
            val credentials = credentialsProvider(null)
            if (credentials?.accessToken.isNullOrBlank()) {
                update { clearData(notConfiguredMessage) }
                return
            }
            val quota = try {
                fetch(checkNotNull(credentials))
            } catch (exception: OpenCodeQuotaException) {
                if (exception.statusCode != 401) throw exception
                val refreshed = credentialsProvider(credentials.accessToken)
                if (refreshed?.accessToken.isNullOrBlank()) {
                    update { clearData(notConfiguredMessage) }
                    return
                }
                fetch(checkNotNull(refreshed))
            }
            update { storeQuota(quota, quota.rawJson) }
        } catch (exception: OpenCodeQuotaException) {
            update { storeFetchFailure(exception.statusCode, exception.message ?: "OpenCode request failed", exception.rawBody) }
        } catch (exception: Exception) {
            update { storeError(exception.message ?: "OpenCode request failed") }
        }
    }

    private fun fetch(credentials: OAuthCredentials): OpenCodeQuota {
        val token = checkNotNull(credentials.accessToken)
        if (lastToken.getAndSet(token) != token) resetWorkspaceCache()
        val settings = settingsProvider()
        // Browser-selected organization scope wins over any workspace left by an earlier login.
        val workspace = credentials.accountId?.takeIf { it.isNotBlank() }
            ?: settings?.openCodeWorkspaceIdFor(accountId)?.takeIf { it.isNotBlank() }
            ?: cachedWorkspaceId.get()
            ?: openCodeClient.discoverWorkspaceId(token)
        cachedWorkspaceId.set(workspace)
        if (settings != null && settings.openCodeWorkspaceIdFor(accountId) != workspace) {
            settings.setOpenCodeWorkspaceIdFor(accountId, workspace)
        }
        return openCodeClient.fetchQuota(token, workspace)
    }

    override fun clearData(error: String?) {
        synchronized(dataLock) {
            generation++
            resetWorkspaceCache()
            lastToken.set(null)
            super.clearData(error)
        }
    }

    fun resetWorkspaceCache() {
        cachedWorkspaceId.set(null)
    }
}
