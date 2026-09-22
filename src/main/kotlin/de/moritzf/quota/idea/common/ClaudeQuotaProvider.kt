package de.moritzf.quota.idea.common

import de.moritzf.quota.claude.ClaudeQuota
import de.moritzf.quota.claude.ClaudeQuotaClient
import de.moritzf.quota.claude.ClaudeQuotaException
import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.auth.OAuthConnectionState

class ClaudeQuotaProvider(
    override val accountId: String = QuotaProviderType.CLAUDE.id,
    private val client: ClaudeQuotaClient = ClaudeQuotaClient(),
    private val tokenProvider: () -> String? = {
        QuotaAuthService.getInstance().getAccessTokenBlocking(accountId, QuotaProviderType.CLAUDE)
    },
    private val tokenRefresher: (staleAccessToken: String?) -> String? = { staleToken ->
        QuotaAuthService.getInstance().forceRefreshBlocking(accountId, QuotaProviderType.CLAUDE, staleToken)
    },
    private val connectionStateProvider: () -> OAuthConnectionState = {
        QuotaAuthService.getInstance().connectionState(accountId, QuotaProviderType.CLAUDE)
    },
    private val reconnectRequired: (String) -> Unit = { rejectedToken ->
        QuotaAuthService.getInstance().requireReconnect(accountId, QuotaProviderType.CLAUDE, rejectedToken)
    },
) : CachedQuotaProvider<ClaudeQuota>() {
    override val type = QuotaProviderType.CLAUDE
    override val notConfiguredMessage = "Claude login required. Log in from Claude settings."

    override fun refresh() {
        val accessToken = tokenProvider()
        if (accessToken.isNullOrBlank()) {
            storeMissingAccessToken(connectionStateProvider(), TOKEN_UNAVAILABLE_MESSAGE)
            return
        }

        try {
            val quota = fetchQuotaWithAuthRetry(accessToken) ?: return
            storeQuota(quota, quota.rawJson)
        } catch (exception: ClaudeQuotaException) {
            storeFetchFailure(exception.statusCode, exception.message ?: "Request failed", exception.rawBody)
        } catch (exception: Exception) {
            storeError(exception.message ?: "Request failed")
        }
    }

    private fun fetchQuotaWithAuthRetry(accessToken: String): ClaudeQuota? {
        return try {
            client.fetchQuota(accessToken)
        } catch (exception: ClaudeQuotaException) {
            val missingProfileScope = exception.statusCode == 403 &&
                exception.rawBody?.contains("user:profile", ignoreCase = true) == true
            if (missingProfileScope) {
                reconnectRequired(accessToken)
                throw exception
            }
            if (exception.statusCode != 401 && exception.statusCode != 403) throw exception
            val refreshed = tokenRefresher(accessToken)?.takeIf { it.isNotBlank() && it != accessToken }
            if (refreshed == null) {
                storeMissingAccessToken(connectionStateProvider(), TOKEN_UNAVAILABLE_MESSAGE)
                return null
            }
            try {
                client.fetchQuota(refreshed)
            } catch (retryFailure: ClaudeQuotaException) {
                if (retryFailure.statusCode == 401 ||
                    (retryFailure.statusCode == 403 && retryFailure.rawBody?.contains("user:profile", ignoreCase = true) == true)
                ) {
                    reconnectRequired(refreshed)
                }
                throw retryFailure
            }
        }
    }

    private companion object {
        private const val TOKEN_UNAVAILABLE_MESSAGE =
            "Claude token could not be refreshed. Trying again with the next update."
    }
}
