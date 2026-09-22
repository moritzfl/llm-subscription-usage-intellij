package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.auth.OAuthConnectionState
import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.openai.OpenAiCodexQuotaClient
import de.moritzf.quota.openai.OpenAiCodexQuotaException
import de.moritzf.quota.openai.UsageWindow
import kotlin.time.Clock

/**
 * Fetches and caches OpenAI Codex quota data.
 */
class OpenAiQuotaProvider(
    override val accountId: String = QuotaProviderType.OPEN_AI.id,
    private val quotaFetcher: (String, String?) -> OpenAiCodexQuota = { accessToken, chatgptAccountId ->
        OpenAiCodexQuotaClient().fetchQuota(accessToken, chatgptAccountId)
    },
    private val resetCreditConsumer: (String, String?, String?) -> Unit = { accessToken, chatgptAccountId, creditId ->
        OpenAiCodexQuotaClient().consumeResetCredit(accessToken, chatgptAccountId, creditId)
    },
    private val accessTokenProvider: () -> String? = {
        QuotaAuthService.getInstance().getAccessTokenBlocking(accountId, QuotaProviderType.OPEN_AI)
    },
    private val accountIdProvider: () -> String? = {
        QuotaAuthService.getInstance().getAccountId(accountId, QuotaProviderType.OPEN_AI)
    },
    private val tokenRefresher: (staleAccessToken: String?) -> String? = { staleToken ->
        QuotaAuthService.getInstance().forceRefreshBlocking(accountId, QuotaProviderType.OPEN_AI, staleToken)
    },
    private val connectionStateProvider: () -> OAuthConnectionState = {
        QuotaAuthService.getInstance().connectionState(accountId, QuotaProviderType.OPEN_AI)
    },
) : CachedQuotaProvider<OpenAiCodexQuota>() {
    override val type = QuotaProviderType.OPEN_AI
    override val notConfiguredMessage = "Not logged in"

    override fun refresh() {
        refresh(forceUpdate = false)
    }

    fun refresh(forceUpdate: Boolean) {
        val accessToken = accessTokenProvider()
        if (accessToken.isNullOrBlank()) {
            storeMissingAccessToken(
                connectionStateProvider(),
                "OpenAI token could not be refreshed. Trying again with the next update.",
            )
            return
        }

        try {
            val quota = fetchQuotaWithAuthRetry(accessToken)
            if (!forceUpdate) {
                applyHysteresis(lastQuotaRef.get(), quota)
            }
            storeQuota(quota, quota.rawJson)
        } catch (exception: OpenAiCodexQuotaException) {
            val detail = exception.message?.takeIf { it.isNotBlank() && !it.startsWith("Request failed") }
            storeFetchFailure(
                exception.statusCode,
                detail ?: "Request failed (${exception.statusCode})",
                exception.rawBody,
            )
        } catch (exception: Exception) {
            storeError(exception.message ?: "Request failed")
        }
    }

    private fun fetchQuotaWithAuthRetry(accessToken: String): OpenAiCodexQuota {
        return try {
            quotaFetcher(accessToken, accountIdProvider())
        } catch (exception: OpenAiCodexQuotaException) {
            if (exception.statusCode != 401 && exception.statusCode != 403) throw exception
            val refreshed = tokenRefresher(accessToken)?.takeIf { it.isNotBlank() && it != accessToken }
                ?: throw exception
            quotaFetcher(refreshed, accountIdProvider())
        }
    }

    fun consumeResetCredit(creditId: String?) {
        val accessToken = accessTokenProvider()
        if (accessToken.isNullOrBlank()) {
            throw IllegalStateException("Not logged in")
        }
        resetCreditConsumer(accessToken, accountIdProvider(), creditId)
    }

    private fun applyHysteresis(oldQuota: OpenAiCodexQuota?, newQuota: OpenAiCodexQuota) {
        if (oldQuota == null) return

        var anyLimitReached = false

        fun stabilizeWindow(oldWindow: UsageWindow?, newWindow: UsageWindow?, oldLimitReached: Boolean?) {
            if (oldWindow == null || newWindow == null) return

            val wasLimitReached = oldLimitReached == true || oldWindow.usedPercent >= 100.0
            val isLimitReached = newWindow.usedPercent >= 100.0

            if (wasLimitReached && !isLimitReached && newWindow.usedPercent >= 99.0) {
                val oldResetTime = oldWindow.resetsAt
                if (oldResetTime != null && Clock.System.now() < oldResetTime) {
                    newWindow.usedPercent = 100.0
                    anyLimitReached = true
                }
            } else if (isLimitReached) {
                anyLimitReached = true
            }
        }

        stabilizeWindow(oldQuota.primary, newQuota.primary, oldQuota.limitReached)
        stabilizeWindow(oldQuota.secondary, newQuota.secondary, oldQuota.limitReached)

        if (anyLimitReached) {
            newQuota.limitReached = true
        }

        var anyReviewLimitReached = false

        fun stabilizeReviewWindow(oldWindow: UsageWindow?, newWindow: UsageWindow?, oldLimitReached: Boolean?) {
            if (oldWindow == null || newWindow == null) return

            val wasLimitReached = oldLimitReached == true || oldWindow.usedPercent >= 100.0
            val isLimitReached = newWindow.usedPercent >= 100.0

            if (wasLimitReached && !isLimitReached && newWindow.usedPercent >= 99.0) {
                val oldResetTime = oldWindow.resetsAt
                if (oldResetTime != null && Clock.System.now() < oldResetTime) {
                    newWindow.usedPercent = 100.0
                    anyReviewLimitReached = true
                }
            } else if (isLimitReached) {
                anyReviewLimitReached = true
            }
        }

        stabilizeReviewWindow(oldQuota.reviewPrimary, newQuota.reviewPrimary, oldQuota.reviewLimitReached)
        stabilizeReviewWindow(oldQuota.reviewSecondary, newQuota.reviewSecondary, oldQuota.reviewLimitReached)

        if (anyReviewLimitReached) {
            newQuota.reviewLimitReached = true
        }
    }
}
