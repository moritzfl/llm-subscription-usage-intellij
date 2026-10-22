package de.moritzf.quota.idea.common

import de.moritzf.quota.claude.ClaudeQuota
import de.moritzf.quota.claude.ClaudeQuotaClient
import de.moritzf.quota.claude.ClaudeQuotaException
import de.moritzf.quota.claude.ClaudeResetChoice
import de.moritzf.quota.claude.ClaudeResetOutcome
import de.moritzf.quota.claude.ClaudeResets
import de.moritzf.quota.idea.auth.OAuthConnectionState
import de.moritzf.quota.idea.auth.QuotaAuthService
import java.util.concurrent.atomic.AtomicReference

class ClaudeQuotaProvider(
    override val accountId: String = QuotaProviderType.CLAUDE.id,
    private val client: ClaudeQuotaClient = ClaudeQuotaClient(),
    private val tokenProvider: () -> String? = {
        QuotaAuthService.getInstance().getAccessTokenBlocking(accountId, QuotaProviderType.CLAUDE)
    },
    private val tokenRefresher: (staleAccessToken: String?) -> String? = { staleToken ->
        QuotaAuthService.getInstance()
            .forceRefreshBlocking(accountId, QuotaProviderType.CLAUDE, staleToken)
    },
    private val connectionStateProvider: () -> OAuthConnectionState = {
        QuotaAuthService.getInstance().connectionState(accountId, QuotaProviderType.CLAUDE)
    },
    private val reconnectRequired: (String) -> Unit = { rejectedToken ->
        QuotaAuthService.getInstance()
            .requireReconnect(accountId, QuotaProviderType.CLAUDE, rejectedToken)
    },
) : CachedQuotaProvider<ClaudeQuota>() {
    override val type = QuotaProviderType.CLAUDE
    override val notConfiguredMessage = "Claude login required. Log in from Claude settings."
    private val organizationUuid = AtomicReference<String?>()

    override fun refresh() {
        val accessToken = tokenProvider()
        if (accessToken.isNullOrBlank()) {
            storeMissingAccessToken(connectionStateProvider(), TOKEN_UNAVAILABLE_MESSAGE)
            return
        }

        try {
            val quota = fetchQuotaWithAuthRetry(accessToken) ?: return
            val resets = fetchResets(quota)
            storeQuota(
                if (resets == quota.resets) quota else quota.copy(resets = resets),
                quota.rawJson,
            )
        } catch (exception: ClaudeQuotaException) {
            storeFetchFailure(
                exception.statusCode,
                exception.message ?: "Request failed",
                exception.rawBody,
            )
        } catch (exception: Exception) {
            storeError(exception.message ?: "Request failed")
        }
    }

    /** Best effort: when the read fails, the resets from the last reading stay. */
    private fun fetchResets(quota: ClaudeQuota): ClaudeResets? {
        val token = tokenProvider()?.takeIf { it.isNotBlank() } ?: return null
        return try {
            client.fetchResets(token, quota.atLimit())
        } catch (exception: Exception) {
            exception.rethrowIfCancellation()
            lastQuotaRef.get()?.resets
        }
    }

    /** Uses one reset and returns the server's answer; nothing was used unless it succeeded. */
    fun redeemReset(choice: ClaudeResetChoice): ClaudeResetOutcome {
        val accessToken =
            tokenProvider()?.takeIf { it.isNotBlank() }
                ?: throw ClaudeQuotaException(notConfiguredMessage)
        val redeem = { token: String ->
            val organization =
                organizationUuid.get()
                    ?: client.fetchOrganizationUuid(token).also { organizationUuid.set(it) }
            client.redeemReset(token, organization, choice)
        }
        return try {
            redeem(accessToken)
        } catch (exception: ClaudeQuotaException) {
            if (exception.statusCode != 401 && exception.statusCode != 403) throw exception
            val refreshed =
                tokenRefresher(accessToken)?.takeIf { it.isNotBlank() && it != accessToken }
                    ?: throw exception
            redeem(refreshed)
        }
    }

    private fun fetchQuotaWithAuthRetry(accessToken: String): ClaudeQuota? {
        return try {
            client.fetchQuota(accessToken)
        } catch (exception: ClaudeQuotaException) {
            val missingProfileScope =
                exception.statusCode == 403 &&
                    exception.rawBody?.contains("user:profile", ignoreCase = true) == true
            if (missingProfileScope) {
                reconnectRequired(accessToken)
                throw exception
            }
            if (exception.statusCode != 401 && exception.statusCode != 403) throw exception
            val refreshed =
                tokenRefresher(accessToken)?.takeIf { it.isNotBlank() && it != accessToken }
            if (refreshed == null) {
                storeMissingAccessToken(connectionStateProvider(), TOKEN_UNAVAILABLE_MESSAGE)
                return null
            }
            try {
                client.fetchQuota(refreshed)
            } catch (retryFailure: ClaudeQuotaException) {
                if (
                    retryFailure.statusCode == 401 ||
                        (retryFailure.statusCode == 403 &&
                            retryFailure.rawBody?.contains("user:profile", ignoreCase = true) ==
                                true)
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
