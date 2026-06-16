package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.supergrok.SuperGrokQuota
import de.moritzf.quota.supergrok.SuperGrokQuotaClient
import de.moritzf.quota.supergrok.SuperGrokQuotaException

class SuperGrokQuotaProvider(
    private val client: SuperGrokQuotaClient = SuperGrokQuotaClient(),
    private val tokenProvider: () -> String? = {
        QuotaAuthService.getInstance().getAccessTokenBlocking(QuotaProviderType.SUPERGROK)
    },
) : CachedQuotaProvider<SuperGrokQuota>() {
    override val type = QuotaProviderType.SUPERGROK
    override val notConfiguredMessage = "Grok login required. Log in from SuperGrok settings."

    override fun refresh() {
        val accessToken = tokenProvider()
        if (accessToken.isNullOrBlank()) {
            clearData(notConfiguredMessage)
            return
        }

        try {
            val quota = client.fetchQuota(accessToken)
            storeQuota(quota, quota.rawJson)
        } catch (exception: SuperGrokQuotaException) {
            storeError(exception.message ?: "Request failed", exception.rawBody)
        } catch (exception: Exception) {
            storeError(exception.message ?: "Request failed")
        }
    }
}
