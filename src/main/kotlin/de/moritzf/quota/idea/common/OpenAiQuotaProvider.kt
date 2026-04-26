package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.openai.OpenAiCodexQuotaClient
import de.moritzf.quota.openai.OpenAiCodexQuotaException
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
}
