package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.mistral.MistralApiKeyStore
import de.moritzf.quota.idea.mistral.MistralSessionCookieStore
import de.moritzf.quota.mistral.MistralQuota
import de.moritzf.quota.mistral.MistralQuotaClient
import de.moritzf.quota.mistral.MistralQuotaException

class MistralQuotaProvider(
    override val accountId: String = QuotaProviderType.MISTRAL.id,
    private val client: MistralQuotaClient = MistralQuotaClient(),
    private val cookieProvider: () -> String? = {
        MistralSessionCookieStore.forAccount(accountId).loadBlocking()
    },
    private val apiKeyProvider: () -> String? = {
        MistralApiKeyStore.forAccount(accountId).loadBlocking()
    },
) : CachedQuotaProvider<MistralQuota>() {
    override val type = QuotaProviderType.MISTRAL
    override val notConfiguredMessage =
        "Mistral session cookie missing. Add the ory_session_* cookie from admin.mistral.ai in settings."

    override fun refresh() {
        val cookie = cookieProvider()
        if (cookie.isNullOrBlank()) {
            clearData(notConfiguredMessage)
            return
        }
        try {
            val apiKey = apiKeyProvider()
            val quota = keepLastPerMinuteWindows(client.fetchQuota(cookie, apiKey), apiKey)
            storeQuota(quota, quota.rawJson)
        } catch (exception: MistralQuotaException) {
            storeFetchFailure(
                exception.statusCode,
                exception.message ?: "Request failed. Check your connection.",
                exception.rawBody,
            )
        }
    }

    private fun keepLastPerMinuteWindows(quota: MistralQuota, apiKey: String?): MistralQuota {
        if (apiKey.isNullOrBlank()) {
            return quota
        }
        val last = lastQuotaRef.get() ?: return quota
        if (quota.tokenUsage != null && quota.requestUsage != null) {
            return quota
        }
        return quota.copy(
            tokenUsage = quota.tokenUsage ?: last.tokenUsage,
            requestUsage = quota.requestUsage ?: last.requestUsage,
        )
    }
}
