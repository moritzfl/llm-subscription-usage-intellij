package de.moritzf.quota.idea.common

import de.moritzf.quota.gemini.GeminiQuota
import de.moritzf.quota.gemini.GeminiQuotaClient
import de.moritzf.quota.gemini.GeminiQuotaException
import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.settings.QuotaSettingsState
import java.util.concurrent.atomic.AtomicReference

class GeminiQuotaProvider(
    private val quotaFetcher: (String, String?) -> GeminiQuota = { accessToken, projectId ->
        GeminiQuotaClient().fetchQuota(accessToken, projectId)
    },
    private val accessTokenProvider: () -> String? = { QuotaAuthService.getInstance().getAccessTokenBlocking(QuotaProviderType.GEMINI) },
) : QuotaProvider {
    override val type = QuotaProviderType.GEMINI

    private val lastQuotaRef = AtomicReference<GeminiQuota?>()
    private val lastErrorRef = AtomicReference<String?>()
    private val lastRawJsonRef = AtomicReference<String?>()

    fun getLastQuota(): GeminiQuota? = lastQuotaRef.get()
    fun getLastError(): String? = lastErrorRef.get()
    fun getLastRawJson(): String? = lastRawJsonRef.get()

    override fun refresh() {
        val accessToken = accessTokenProvider()
        if (accessToken.isNullOrBlank()) {
            clearData("Not logged in")
            return
        }

        try {
            val quota = quotaFetcher(accessToken, null)
            
            val hd = QuotaAuthService.getInstance().getHd(QuotaProviderType.GEMINI)
            if (quota.planType == "free-tier" && !hd.isNullOrBlank()) {
                quota.planType = "Workspace"
            } else if (quota.planType == "free-tier") {
                quota.planType = "Free"
            } else if (quota.planType == "standard-tier") {
                val paidName = quota.paidTierName
                if (!paidName.isNullOrBlank()) {
                    quota.planType = paidName.replace("Gemini Code Assist in ", "").replace("Gemini Code Assist", "Paid")
                } else {
                    quota.planType = "Paid"
                }
            } else if (quota.planType == "legacy-tier") {
                quota.planType = "Legacy"
            }
            
            quota.accountEmail = QuotaAuthService.getInstance().getAccountId(QuotaProviderType.GEMINI)

            lastQuotaRef.set(quota)
            lastErrorRef.set(null)
            lastRawJsonRef.set(quota.rawJson)
        } catch (exception: GeminiQuotaException) {
            lastQuotaRef.set(null)
            lastErrorRef.set("Request failed (" + exception.statusCode + ")")
            lastRawJsonRef.set(exception.responseBody)
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
        val cached = QuotaSnapshotCache.decodeGeminiQuota(settings.cachedGeminiQuotaJson)
        lastQuotaRef.set(cached)
        lastRawJsonRef.set(cached?.rawJson)
    }

    override fun persistToCache(settings: QuotaSettingsState) {
        val quota = lastQuotaRef.get()
        if (quota != null) {
            QuotaSnapshotCache.encodeGeminiQuota(quota)?.let { settings.cachedGeminiQuotaJson = it }
            settings.updateTimestamp(type)
        }
    }
}
