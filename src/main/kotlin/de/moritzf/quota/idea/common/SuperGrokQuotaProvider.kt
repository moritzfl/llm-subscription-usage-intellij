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
    private val tokenRefresher: (staleAccessToken: String?) -> String? = { staleToken ->
        QuotaAuthService.getInstance().forceRefreshBlocking(QuotaProviderType.SUPERGROK, staleToken)
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
            val quota = fetchQuotaWithAuthRetry(accessToken)
            storeQuota(quota, quota.rawJson)
        } catch (exception: SuperGrokQuotaException) {
            storeError(exception.message ?: "Request failed", exception.rawBody)
        } catch (exception: Exception) {
            storeError(exception.message ?: "Request failed")
        }
    }

    private fun fetchQuotaWithAuthRetry(accessToken: String): SuperGrokQuota {
        return try {
            client.fetchQuota(accessToken)
        } catch (exception: SuperGrokQuotaException) {
            if (exception.statusCode != 401 && exception.statusCode != 403) throw exception
            val refreshed = tokenRefresher(accessToken)?.takeIf { it.isNotBlank() && it != accessToken }
                ?: throw exception
            client.fetchQuota(refreshed)
        }
    }
}
