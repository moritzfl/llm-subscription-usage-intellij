package de.moritzf.quota.idea.common

import de.moritzf.quota.claude.ClaudeQuota
import de.moritzf.quota.claude.ClaudeQuotaClient
import de.moritzf.quota.claude.ClaudeQuotaException
import de.moritzf.quota.idea.auth.QuotaAuthService

class ClaudeQuotaProvider(
    private val client: ClaudeQuotaClient = ClaudeQuotaClient(),
    private val tokenProvider: () -> String? = {
        QuotaAuthService.getInstance().getAccessTokenBlocking(QuotaProviderType.CLAUDE)
    },
    private val tokenRefresher: (staleAccessToken: String?) -> String? = { staleToken ->
        QuotaAuthService.getInstance().forceRefreshBlocking(QuotaProviderType.CLAUDE, staleToken)
    },
) : CachedQuotaProvider<ClaudeQuota>() {
    override val type = QuotaProviderType.CLAUDE
    override val notConfiguredMessage = "Claude login required. Log in from Claude settings."

    override fun refresh() {
        val accessToken = tokenProvider()
        if (accessToken.isNullOrBlank()) {
            clearData(notConfiguredMessage)
            return
        }

        try {
            val quota = fetchQuotaWithAuthRetry(accessToken)
            storeQuota(quota, quota.rawJson)
        } catch (exception: ClaudeQuotaException) {
            storeError(exception.message ?: "Request failed", exception.rawBody)
        } catch (exception: Exception) {
            storeError(exception.message ?: "Request failed")
        }
    }

    private fun fetchQuotaWithAuthRetry(accessToken: String): ClaudeQuota {
        return try {
            client.fetchQuota(accessToken)
        } catch (exception: ClaudeQuotaException) {
            if (exception.statusCode != 401 && exception.statusCode != 403) throw exception
            val refreshed = tokenRefresher(accessToken)?.takeIf { it.isNotBlank() && it != accessToken }
                ?: throw exception
            client.fetchQuota(refreshed)
        }
    }
}
