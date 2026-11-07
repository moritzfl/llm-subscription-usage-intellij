package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.auth.OAuthConnectionState
import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.minimax.MiniMaxApiKeyStore
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.minimax.MiniMaxCodeAccount
import de.moritzf.quota.minimax.MiniMaxCodeAccountClient
import de.moritzf.quota.minimax.MiniMaxCodeHosts
import de.moritzf.quota.minimax.MiniMaxQuota
import de.moritzf.quota.minimax.MiniMaxQuotaClient
import de.moritzf.quota.minimax.MiniMaxQuotaException
import de.moritzf.quota.minimax.MiniMaxQuotaSources
import de.moritzf.quota.minimax.MiniMaxRegion
import de.moritzf.quota.minimax.MiniMaxRegionPreference
import kotlin.time.Clock

/** The optional MiniMax Code login of one MiniMax account. */
interface MiniMaxCodeLogin {
    fun isLoggedIn(): Boolean

    fun accessToken(): String?

    /** A token after the API rejected [rejectedAccessToken], or null when none is available. */
    fun refreshAfterRejection(rejectedAccessToken: String): String?

    fun requireReconnect(rejectedAccessToken: String)

    fun connectionState(): OAuthConnectionState

    fun userId(): String?

    fun region(): MiniMaxRegion
}

private class QuotaAuthMiniMaxCodeLogin(private val accountId: String) : MiniMaxCodeLogin {
    private val auth
        get() = QuotaAuthService.getInstance()

    private val type = QuotaProviderType.MINIMAX

    override fun isLoggedIn() = auth.hasCredentialsBlocking(accountId, type)

    override fun accessToken() = auth.getAccessTokenBlocking(accountId, type)

    override fun refreshAfterRejection(rejectedAccessToken: String) =
        auth.forceRefreshBlocking(accountId, type, rejectedAccessToken)

    override fun requireReconnect(rejectedAccessToken: String) =
        auth.requireReconnect(accountId, type, rejectedAccessToken)

    override fun connectionState() = auth.connectionState(accountId, type)

    override fun userId() = auth.getAccountId(accountId, type)

    override fun region() =
        MiniMaxCodeHosts.regionOfIssuer(auth.getIssuer(accountId, type)) ?: MiniMaxRegion.GLOBAL
}

class MiniMaxQuotaProvider(
    override val accountId: String = QuotaProviderType.MINIMAX.id,
    private val client: MiniMaxQuotaClient = MiniMaxQuotaClient(),
    private val settingsProvider: () -> QuotaSettingsState? = {
        runCatching { QuotaSettingsState.getInstance() }.getOrNull()
    },
    private val apiKeyProvider: () -> String? = {
        MiniMaxApiKeyStore.forAccount(accountId).loadBlocking()
    },
    private val codeClient: MiniMaxCodeAccountClient = MiniMaxCodeAccountClient(),
    private val codeLogin: MiniMaxCodeLogin = QuotaAuthMiniMaxCodeLogin(accountId),
) : CachedQuotaProvider<MiniMaxQuota>() {
    override val type = QuotaProviderType.MINIMAX
    override val notConfiguredMessage =
        "MiniMax not connected. Add a subscription key or sign in with MiniMax Code in settings."

    override fun refresh() {
        val apiKey = apiKeyProvider()?.takeIf { it.isNotBlank() }
        val loggedIn = codeLogin.isLoggedIn()
        if (apiKey == null && !loggedIn) {
            clearData(notConfiguredMessage)
            return
        }

        var keyQuota: MiniMaxQuota? = null
        var keyFailure: MiniMaxQuotaException? = null
        if (apiKey != null) {
            try {
                keyQuota = fetchWithKey(apiKey)
            } catch (exception: MiniMaxQuotaException) {
                keyFailure = exception
            }
        }
        if (!loggedIn) {
            keyQuota?.let { storeQuota(it, it.rawJson) } ?: storeKeyFailure(keyFailure, null)
            return
        }

        val region = codeLogin.region()
        var account: MiniMaxCodeAccount? = null
        var accountFailure: MiniMaxQuotaException? = null
        try {
            account = fetchAccount(region, includeTokenPlan = apiKey == null)
        } catch (exception: MiniMaxQuotaException) {
            accountFailure = exception
        }
        val raw =
            MiniMaxQuotaSources.rawResponse(
                keyConfigured = apiKey != null,
                keyBody = keyQuota?.rawJson ?: keyFailure?.rawBody,
                keyError = keyFailure?.message,
                account = account,
                accountError = accountFailure?.message,
            )
        // A configured key stays the source of the plan windows; its failure is the reading's.
        if (keyFailure != null || account == null && keyQuota == null) {
            storeKeyFailure(keyFailure ?: accountFailure, raw)
            return
        }
        val quota =
            MiniMaxQuotaSources.combine(
                keyQuota,
                account,
                region,
                listOfNotNull(accountFailure?.let { "MiniMax Code: ${it.message}" }),
            )
        quota.fetchedAt = Clock.System.now()
        quota.rawJson = raw
        storeQuota(quota, raw)
    }

    private fun fetchWithKey(apiKey: String): MiniMaxQuota {
        val preference =
            settingsProvider()?.miniMaxRegionFor(accountId) ?: MiniMaxRegionPreference.AUTO
        val regions =
            when (preference) {
                MiniMaxRegionPreference.GLOBAL -> listOf(MiniMaxRegion.GLOBAL)
                MiniMaxRegionPreference.CN -> listOf(MiniMaxRegion.CN)
                MiniMaxRegionPreference.AUTO -> listOf(MiniMaxRegion.GLOBAL, MiniMaxRegion.CN)
            }
        var reported: MiniMaxQuotaException? = null
        for (region in regions) {
            try {
                return client.fetchQuota(apiKey, region)
            } catch (exception: MiniMaxQuotaException) {
                // A key belongs to one region, so the other region's rejection says nothing
                // about it: keep the failure from the region that accepted the key.
                if (reported == null || reported.isRejectedKey() || !exception.isRejectedKey()) {
                    reported = exception
                }
            }
        }
        throw reported ?: MiniMaxQuotaException("Request failed. Check your connection.")
    }

    private fun storeKeyFailure(failure: MiniMaxQuotaException?, raw: String?) {
        storeFetchFailure(
            failure?.statusCode ?: 0,
            failure?.message ?: "Request failed. Check your connection.",
            raw ?: failure?.rawBody,
        )
    }

    private fun fetchAccount(region: MiniMaxRegion, includeTokenPlan: Boolean): MiniMaxCodeAccount {
        val token =
            codeLogin.accessToken()
                ?: throw MiniMaxQuotaException(
                    when (codeLogin.connectionState()) {
                        OAuthConnectionState.RECONNECT_REQUIRED,
                        OAuthConnectionState.LOGGED_OUT ->
                            "MiniMax Code login expired. Sign in again in settings."
                        else -> "MiniMax Code login could not be refreshed. Trying again later."
                    },
                    401,
                )
        return try {
            readAccount(token, region, includeTokenPlan)
        } catch (exception: MiniMaxQuotaException) {
            if (exception.statusCode != 401) throw exception
            val refreshed =
                codeLogin.refreshAfterRejection(token)?.takeIf { it != token }
                    ?: run {
                        codeLogin.requireReconnect(token)
                        throw exception
                    }
            try {
                readAccount(refreshed, region, includeTokenPlan)
            } catch (retry: MiniMaxQuotaException) {
                if (retry.statusCode == 401) codeLogin.requireReconnect(refreshed)
                throw retry
            }
        }
    }

    private fun readAccount(
        token: String,
        region: MiniMaxRegion,
        includeTokenPlan: Boolean,
    ): MiniMaxCodeAccount {
        val userId = codeLogin.userId() ?: codeClient.fetchUserId(token, region)
        return codeClient.fetchAccount(token, region, userId, includeTokenPlan)
    }

    private fun MiniMaxQuotaException.isRejectedKey(): Boolean {
        return statusCode == 401 || statusCode == 403
    }
}
