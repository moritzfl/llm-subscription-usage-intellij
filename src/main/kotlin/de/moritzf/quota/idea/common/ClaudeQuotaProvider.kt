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
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

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
    private val clock: () -> Instant = { Clock.System.now() },
) : CachedQuotaProvider<ClaudeQuota>() {
    override val type = QuotaProviderType.CLAUDE
    override val notConfiguredMessage = "Claude login required. Log in from Claude settings."
    private val organizationUuid = AtomicReference<String?>()
    private val rateLimit = AtomicReference<RateLimitPause?>()

    override fun refresh() {
        val accessToken = tokenProvider()
        if (accessToken.isNullOrBlank()) {
            storeMissingAccessToken(connectionStateProvider(), TOKEN_UNAVAILABLE_MESSAGE)
            return
        }
        // Like Claude Code, do not ask again while the usage API is rate limiting this login.
        if (rateLimit.get()?.let { it.token == accessToken && clock() < it.until } == true) return

        try {
            val quota = fetchQuotaWithAuthRetry(accessToken) ?: return
            rateLimit.set(null)
            val resets =
                if (quota.atLimit()) ClaudeResets.merge(quota.resets, fetchAtLimitResets())
                else quota.resets
            storeQuota(
                if (resets == quota.resets) quota else quota.copy(resets = resets),
                quota.rawJson,
            )
        } catch (exception: ClaudeQuotaException) {
            val message = exception.message ?: "Request failed"
            storeFetchFailure(
                exception.statusCode,
                if (exception.statusCode == 429)
                    pauseAfterRateLimit(exception, message, accessToken)
                else message,
                exception.rawBody,
            )
        } catch (exception: Exception) {
            storeError(exception.message ?: "Request failed")
        }
    }

    /**
     * Pauses usage reads for the server's `Retry-After`, otherwise for 5 minutes doubling with each
     * rate limit in a row, at most an hour. Returns the error with the time of the next attempt.
     */
    private fun pauseAfterRateLimit(
        exception: ClaudeQuotaException,
        message: String,
        token: String,
    ): String {
        val previous = rateLimit.get()?.takeIf { it.token == token }
        val count = (previous?.count ?: 0) + 1
        val requested = exception.retryAfter?.toMillis()?.takeIf { it > 0 }?.milliseconds
        val backoff = RATE_LIMIT_PAUSE * (1 shl (count - 1).coerceAtMost(4))
        val pause = minOf(requested ?: backoff, MAX_RATE_LIMIT_PAUSE)
        val until = clock() + pause
        rateLimit.set(RateLimitPause(until, count, token))
        val time =
            java.time.LocalTime.ofInstant(
                    java.time.Instant.ofEpochMilli(until.toEpochMilliseconds()),
                    java.time.ZoneId.systemDefault(),
                )
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        return "$message Next try at $time."
    }

    /** Best effort: when the read fails, the session reset from the last reading stays. */
    private fun fetchAtLimitResets(): ClaudeResets? {
        val token = tokenProvider()?.takeIf { it.isNotBlank() } ?: return null
        return try {
            client.fetchAtLimitResets(token)
        } catch (exception: Exception) {
            exception.rethrowIfCancellation()
            if (exception is ClaudeQuotaException && exception.statusCode == 429) {
                pauseAfterRateLimit(exception, "", token)
            }
            lastQuotaRef.get()?.resets?.let { ClaudeResets(sessionReset = it.sessionReset) }
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

    private data class RateLimitPause(val until: Instant, val count: Int, val token: String)

    private companion object {
        private const val TOKEN_UNAVAILABLE_MESSAGE =
            "Claude token could not be refreshed. Trying again with the next update."
        private val RATE_LIMIT_PAUSE = 5.minutes
        private val MAX_RATE_LIMIT_PAUSE = 1.hours
    }
}
