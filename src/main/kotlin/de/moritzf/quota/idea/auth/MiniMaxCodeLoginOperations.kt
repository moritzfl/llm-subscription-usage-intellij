package de.moritzf.quota.idea.auth

import de.moritzf.quota.minimax.MiniMaxCodeAccountClient
import de.moritzf.quota.minimax.MiniMaxCodeAuthException
import de.moritzf.quota.minimax.MiniMaxCodeDeviceAuthorization
import de.moritzf.quota.minimax.MiniMaxCodeOAuthClient
import de.moritzf.quota.minimax.MiniMaxCodeTokenResult
import de.moritzf.quota.minimax.MiniMaxRegion
import de.moritzf.quota.shared.auth.OAuthCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/** One MiniMax Code device login in [region]; the account ID is read once it is authorized. */
internal class MiniMaxCodeDeviceLogin(
    private val region: MiniMaxRegion,
    private val oauthClient: MiniMaxCodeOAuthClient = MiniMaxCodeOAuthClient(),
    private val accountClient: MiniMaxCodeAccountClient = MiniMaxCodeAccountClient(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) : OAuthDeviceLoginOperations {
    @Volatile private var authorization: MiniMaxCodeDeviceAuthorization? = null

    override suspend fun requestAuthorization(): OAuthDeviceAuthorization =
        runInterruptible(Dispatchers.IO) {
            val requested = oauthClient.requestDeviceAuthorization(region)
            authorization = requested
            OAuthDeviceAuthorization(
                DeviceLoginPrompt(
                    requested.verificationUrl,
                    requested.userCode,
                    nowMs() + requested.expiresInSeconds * 1000,
                ),
                requested.deviceCode,
                requested.intervalSeconds,
            )
        }

    override suspend fun poll(authorization: OAuthDeviceAuthorization): OAuthDevicePollResult =
        runInterruptible(Dispatchers.IO) {
            val requested = checkNotNull(this.authorization) { "No MiniMax Code login started" }
            when (val result = oauthClient.pollDeviceToken(requested)) {
                MiniMaxCodeTokenResult.Pending -> OAuthDevicePollResult.Pending
                MiniMaxCodeTokenResult.SlowDown -> OAuthDevicePollResult.SlowDown
                is MiniMaxCodeTokenResult.Authorized -> {
                    val credentials = result.credentials
                    // The account API needs the ID; quota reads look it up later when this fails.
                    credentials.accountId =
                        runCatching {
                            accountClient.fetchUserId(checkNotNull(credentials.accessToken), region)
                        }
                            .getOrNull()
                    OAuthDevicePollResult.Authorized(credentials)
                }
            }
        }
}

/** Refreshes MiniMax Code logins; a rejected refresh token surfaces as `invalid_grant`. */
internal class MiniMaxCodeTokenOperations(
    private val oauthClient: MiniMaxCodeOAuthClient = MiniMaxCodeOAuthClient(),
) : OAuthTokenOperations {
    override suspend fun exchangeAuthorizationCode(
        code: String,
        codeVerifier: String,
        state: String?,
    ): OAuthCredentials = throw UnsupportedOperationException("MiniMax Code uses device login")

    override suspend fun refreshCredentials(existing: OAuthCredentials): OAuthCredentials =
        runInterruptible(Dispatchers.IO) {
            try {
                oauthClient.refresh(existing)
            } catch (exception: MiniMaxCodeAuthException) {
                throw OAuthTokenRequestException(
                    exception.message ?: "MiniMax Code token refresh failed",
                    exception.statusCode,
                    exception.oauthError,
                )
            }
        }
}
