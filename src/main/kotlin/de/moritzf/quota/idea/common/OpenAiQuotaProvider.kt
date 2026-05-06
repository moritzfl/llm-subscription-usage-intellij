package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.openai.OpenAiCodexQuotaClient
import de.moritzf.quota.openai.OpenAiCodexQuotaException
import de.moritzf.quota.openai.UsageWindow
import kotlinx.datetime.Clock
import java.util.concurrent.atomic.AtomicReference

/**
 * Fetches and caches OpenAI Codex quota data.
 */
class OpenAiQuotaProvider(
    private val quotaFetcher: (String, String?) -> OpenAiCodexQuota = { accessToken, accountId ->
        OpenAiCodexQuotaClient().fetchQuota(accessToken, accountId)
    },
    private val accessTokenProvider: () -> String? = { QuotaAuthService.getInstance().getAccessTokenBlocking() },
    private val accountIdProvider: () -> String? = { QuotaAuthService.getInstance().getAccountId() },
) : QuotaProvider {
    override val id = "openai"

    private val lastQuotaRef = AtomicReference<OpenAiCodexQuota?>()
    private val lastErrorRef = AtomicReference<String?>()
    private val lastRawJsonRef = AtomicReference<String?>()

    fun getLastQuota(): OpenAiCodexQuota? = lastQuotaRef.get()
    fun getLastError(): String? = lastErrorRef.get()
    fun getLastRawJson(): String? = lastRawJsonRef.get()

    override fun refresh() {
        val accessToken = accessTokenProvider()
        if (accessToken.isNullOrBlank()) {
            clearData("Not logged in")
            return
        }

        try {
            val quota = quotaFetcher(accessToken, accountIdProvider())
            applyHysteresis(lastQuotaRef.get(), quota)
            lastQuotaRef.set(quota)
            lastErrorRef.set(null)
            lastRawJsonRef.set(quota.rawJson)
        } catch (exception: OpenAiCodexQuotaException) {
            lastQuotaRef.set(null)
            lastErrorRef.set("Request failed (${exception.statusCode})")
            lastRawJsonRef.set(exception.rawBody)
        } catch (exception: Exception) {
            lastQuotaRef.set(null)
            lastErrorRef.set(exception.message ?: "Request failed")
        }
    }

    override fun clearData(error: String?) {
        lastQuotaRef.set(null)
        lastErrorRef.set(error)
        lastRawJsonRef.set(null)
    }

    override fun hydrateFromCache(settings: QuotaSettingsState) {
        val cached = QuotaSnapshotCache.decodeOpenAiQuota(settings.cachedOpenAiQuotaJson)
        lastQuotaRef.set(cached)
        lastRawJsonRef.set(cached?.rawJson)
    }

    override fun persistToCache(settings: QuotaSettingsState) {
        val quota = lastQuotaRef.get()
        if (quota != null) {
            QuotaSnapshotCache.encodeOpenAiQuota(quota)?.let { settings.cachedOpenAiQuotaJson = it }
            settings.updateTimestamp(id)
        }
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
