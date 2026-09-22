package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.auth.OAuthConnectionState
import de.moritzf.quota.supergrok.SuperGrokQuota
import de.moritzf.quota.supergrok.SuperGrokQuotaClient
import de.moritzf.quota.supergrok.SuperGrokQuotaException

class SuperGrokQuotaProvider(
    override val accountId: String = QuotaProviderType.SUPERGROK.id,
    private val client: SuperGrokQuotaClient = SuperGrokQuotaClient(),
    private val tokenProvider: () -> String? = {
        QuotaAuthService.getInstance().getAccessTokenBlocking(accountId, QuotaProviderType.SUPERGROK)
    },
    private val tokenRefresher: (staleAccessToken: String?) -> String? = { staleToken ->
        QuotaAuthService.getInstance().forceRefreshBlocking(accountId, QuotaProviderType.SUPERGROK, staleToken)
    },
    private val connectionStateProvider: () -> OAuthConnectionState = {
        QuotaAuthService.getInstance().connectionState(accountId, QuotaProviderType.SUPERGROK)
    },
    private val resetConsumer: (String, String) -> Unit = { accessToken, tokenId ->
        client.redeemReset(accessToken, tokenId)
    },
) : CachedQuotaProvider<SuperGrokQuota>() {
    override val type = QuotaProviderType.SUPERGROK
    override val notConfiguredMessage = "Grok login required. Log in from SuperGrok settings."

    override fun refresh() {
        refresh(forceUpdate = false)
    }

    fun refresh(forceUpdate: Boolean) {
        val accessToken = tokenProvider()
        if (accessToken.isNullOrBlank()) {
            storeMissingAccessToken(
                connectionStateProvider(),
                "Grok token could not be refreshed. Trying again with the next update.",
            )
            return
        }

        try {
            val quota = fetchQuotaWithAuthRetry(accessToken)
            // Config-only mid-period payloads used to wipe real %; keep last good reading
            // when the new value is only an inferred 0% for the same period window.
            // Skip this check after a reset — the new value is the actual result, not an inference.
            if (!forceUpdate && shouldKeepLastQuota(quota)) {
                lastRawJsonRef.set(quota.rawJson)
                return
            }
            storeQuota(quota, quota.rawJson)
        } catch (exception: SuperGrokQuotaException) {
            // Incomplete/flaky billing payloads (missing creditUsagePercent) and timeouts:
            // keep last good reading when we have one. Auth failures still clear the UI.
            if (exception.statusCode != 401 && exception.statusCode != 403 && lastQuotaRef.get() != null) {
                return
            }
            storeError(exception.message ?: "Request failed", exception.rawBody)
        } catch (exception: Exception) {
            if (lastQuotaRef.get() != null) return
            storeError(exception.message ?: "Request failed")
        }
    }

    private fun shouldKeepLastQuota(quota: SuperGrokQuota): Boolean {
        val last = lastQuotaRef.get() ?: return false
        if (!quota.hasUsageState()) return true
        val newUsage = quota.creditUsage ?: return true
        if (newUsage.reported) return false
        val lastUsage = last.creditUsage ?: return false
        if (!lastUsage.reported) return false
        // New period (different reset) → accept inferred 0% instead of stale prior %.
        val lastReset = lastUsage.resetsAt
        val newReset = newUsage.resetsAt
        if (lastReset != null && newReset != null && lastReset != newReset) {
            return false
        }
        return true
    }

    fun consumeReset(tokenId: String?) {
        val accessToken = tokenProvider()
        if (accessToken.isNullOrBlank()) {
            throw SuperGrokQuotaException(notConfiguredMessage)
        }
        val resetId = tokenId?.trim()?.takeIf { it.isNotBlank() }
            ?: lastQuotaRef.get()?.resetTokens?.firstOrNull()?.tokenId
            ?: throw SuperGrokQuotaException("No SuperGrok reset is available.")
        try {
            resetConsumer(accessToken, resetId)
        } catch (exception: SuperGrokQuotaException) {
            if (exception.statusCode != 401 && exception.statusCode != 403) throw exception
            val refreshed = tokenRefresher(accessToken)?.takeIf { it.isNotBlank() && it != accessToken }
                ?: throw exception
            resetConsumer(refreshed, resetId)
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
