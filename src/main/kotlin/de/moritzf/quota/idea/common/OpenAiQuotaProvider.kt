package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.openai.OpenAiCodexQuotaClient
import de.moritzf.quota.openai.OpenAiCodexQuotaException
import de.moritzf.quota.openai.UsageWindow
import kotlin.time.Clock

/**
 * Fetches and caches OpenAI Codex quota data.
 */
class OpenAiQuotaProvider(
    private val quotaFetcher: (String, String?) -> OpenAiCodexQuota = { accessToken, accountId ->
        OpenAiCodexQuotaClient().fetchQuota(accessToken, accountId)
    },
    private val resetCreditConsumer: (String, String?, String?) -> Unit = { accessToken, accountId, creditId ->
        OpenAiCodexQuotaClient().consumeResetCredit(accessToken, accountId, creditId)
    },
    private val accessTokenProvider: () -> String? = { QuotaAuthService.getInstance().getAccessTokenBlocking() },
    private val accountIdProvider: () -> String? = { QuotaAuthService.getInstance().getAccountId() },
) : CachedQuotaProvider<OpenAiCodexQuota>() {
    override val type = QuotaProviderType.OPEN_AI

    override fun refresh() {
        val accessToken = accessTokenProvider()
        if (accessToken.isNullOrBlank()) {
            clearData("Not logged in")
            return
        }

        try {
            val quota = quotaFetcher(accessToken, accountIdProvider())
            applyHysteresis(lastQuotaRef.get(), quota)
            storeQuota(quota, quota.rawJson)
        } catch (exception: OpenAiCodexQuotaException) {
            storeError("Request failed (${exception.statusCode})", exception.rawBody)
        } catch (exception: Exception) {
            storeError(exception.message ?: "Request failed")
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
