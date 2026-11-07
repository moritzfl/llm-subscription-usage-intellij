package de.moritzf.quota.minimax

import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.auth.OAuthCredentials
import de.moritzf.quota.shared.auth.OAuthUrlCodec
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Hosts of MiniMax Code's account login and account APIs in one region. */
data class MiniMaxCodeHosts(val account: URI, val agent: URI, val platform: URI) {
    companion object {
        private val GLOBAL =
            MiniMaxCodeHosts(
                URI.create("https://account.minimax.io"),
                URI.create("https://agent.minimax.io"),
                URI.create("https://platform.minimax.io"),
            )
        private val CN =
            MiniMaxCodeHosts(
                URI.create("https://account.minimax.cn"),
                URI.create("https://agent.minimaxi.com"),
                URI.create("https://www.minimaxi.com"),
            )

        fun forRegion(region: MiniMaxRegion): MiniMaxCodeHosts =
            if (region == MiniMaxRegion.CN) CN else GLOBAL

        /** The region whose account server granted a login, from its stored issuer. */
        fun regionOfIssuer(issuer: String?): MiniMaxRegion? =
            when (issuer?.trimEnd('/')) {
                GLOBAL.account.toString() -> MiniMaxRegion.GLOBAL
                CN.account.toString() -> MiniMaxRegion.CN
                else -> null
            }
    }
}

/** A login failure; [oauthError] is the server's OAuth error code, never a token or body. */
class MiniMaxCodeAuthException(
    message: String,
    val statusCode: Int = 0,
    val oauthError: String? = null,
) : IOException(message)

class MiniMaxCodeDeviceAuthorization(
    val region: MiniMaxRegion,
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
    internal val codeVerifier: String,
    /** MiniMax's account server polls with the user code instead of a device code. */
    internal val pollWithUserCode: Boolean,
)

sealed interface MiniMaxCodeTokenResult {
    class Authorized(val credentials: OAuthCredentials) : MiniMaxCodeTokenResult

    data object Pending : MiniMaxCodeTokenResult

    data object SlowDown : MiniMaxCodeTokenResult
}

/**
 * The device-code login of MiniMax Code (`MiniMax-AI/minimax-code`, `packages/oauth-core`): PKCE,
 * public client `mcode-public`, scope `agent.default`. Refresh tokens rotate; reusing a rotated one
 * is rejected with `invalid_grant` and revokes the whole login, so refreshes must be serialized
 * across IDEs sharing the credential store.
 */
open class MiniMaxCodeOAuthClient(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val hosts: (MiniMaxRegion) -> MiniMaxCodeHosts = MiniMaxCodeHosts::forRegion,
) {
    open fun requestDeviceAuthorization(region: MiniMaxRegion): MiniMaxCodeDeviceAuthorization {
        val verifier = codeVerifier()
        val response =
            postForm(
                hosts(region).account.resolve(DEVICE_PATH),
                mapOf(
                    "client_id" to CLIENT_ID,
                    "scope" to SCOPE,
                    "audience" to AUDIENCE,
                    "code_challenge" to codeChallenge(verifier),
                    "code_challenge_method" to "S256",
                ),
            )
        val body = response.json
        if (response.status !in 200..299 || body == null || body.string("error") != null) {
            throw MiniMaxCodeAuthException(
                "MiniMax Code login could not start (HTTP ${response.status}).",
                response.status,
                body?.string("error"),
            )
        }
        val userCode = body.string("user_code")
        val standardDeviceCode = body.string("device_code")
        val accountExpiry = body.positiveNumber("expired_in")
        val pollWithUserCode =
            standardDeviceCode == null && userCode != null && accountExpiry != null
        val deviceCode = standardDeviceCode ?: userCode.takeIf { pollWithUserCode }
        val verificationUrl =
            body.string("verification_uri_complete")
                ?: body.string("verification_uri")
                ?: body.string("verification_url")
        val expiresIn =
            body.positiveNumber("expires_in")?.toLong()
                ?: accountExpiry?.let { accountExpirySeconds(it.toLong()) }
        val interval = body.positiveNumber("interval")
        val intervalSeconds =
            when {
                interval == null -> 5L
                // The account server's own fields are milliseconds.
                pollWithUserCode -> (interval / 1000).toLong()
                else -> interval.toLong()
            }.coerceIn(1, 60)
        val uri = verificationUrl?.let { runCatching { URI.create(it) }.getOrNull() }
        if (
            deviceCode == null ||
                userCode == null ||
                expiresIn == null ||
                expiresIn <= 0 ||
                uri?.scheme != "https" ||
                uri.host.isNullOrBlank() ||
                uri.userInfo != null
        ) {
            throw MiniMaxCodeAuthException("MiniMax Code returned an unusable login code.")
        }
        return MiniMaxCodeDeviceAuthorization(
            region = region,
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUrl = verificationUrl,
            expiresInSeconds = expiresIn,
            intervalSeconds = intervalSeconds,
            codeVerifier = verifier,
            pollWithUserCode = pollWithUserCode,
        )
    }

    open fun pollDeviceToken(
        authorization: MiniMaxCodeDeviceAuthorization,
    ): MiniMaxCodeTokenResult {
        val code =
            if (authorization.pollWithUserCode) "user_code" to authorization.userCode
            else "device_code" to authorization.deviceCode
        val response =
            postForm(
                hosts(authorization.region).account.resolve(TOKEN_PATH),
                mapOf(
                    "grant_type" to DEVICE_GRANT,
                    code,
                    "client_id" to CLIENT_ID,
                    "code_verifier" to authorization.codeVerifier,
                ),
            )
        val body =
            response.json
                ?: throw MiniMaxCodeAuthException(
                    "MiniMax Code login failed (HTTP ${response.status}).",
                    response.status,
                )
        val error = body.string("error")
        val ok = response.status in 200..299 && error == null
        when (if (ok) body.string("status") else error) {
            "pending",
            "authorization_pending" -> return MiniMaxCodeTokenResult.Pending
            "slow_down" -> return MiniMaxCodeTokenResult.SlowDown
            "denied",
            "access_denied" ->
                throw MiniMaxCodeAuthException(
                    "MiniMax Code login was denied.",
                    response.status,
                    "access_denied",
                )
            "expired",
            "expired_token" ->
                throw MiniMaxCodeAuthException(
                    "The MiniMax Code login code expired. Start a new login.",
                    response.status,
                    "expired_token",
                )
        }
        if (!ok) {
            throw MiniMaxCodeAuthException(
                "MiniMax Code login failed (HTTP ${response.status}).",
                response.status,
                error,
            )
        }
        return MiniMaxCodeTokenResult.Authorized(credentials(body, authorization.region, null))
    }

    open fun refresh(existing: OAuthCredentials): OAuthCredentials {
        val refreshToken =
            existing.refreshToken?.takeIf { it.isNotBlank() }
                ?: throw MiniMaxCodeAuthException(
                    "MiniMax Code login expired. Sign in again.",
                    400,
                    "invalid_grant",
                )
        val region = MiniMaxCodeHosts.regionOfIssuer(existing.issuer) ?: MiniMaxRegion.GLOBAL
        val response =
            postForm(
                hosts(region).account.resolve(TOKEN_PATH),
                mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshToken,
                    "client_id" to CLIENT_ID,
                    "scope" to SCOPE,
                    "audience" to AUDIENCE,
                ),
            )
        val body = response.json
        val error = body?.string("error")
        if (response.status !in 200..299 || body == null || error != null) {
            throw MiniMaxCodeAuthException(
                "MiniMax Code token refresh failed (HTTP ${response.status}).",
                response.status,
                error,
            )
        }
        return credentials(body, region, existing)
    }

    private fun credentials(
        body: JsonObject,
        region: MiniMaxRegion,
        previous: OAuthCredentials?,
    ): OAuthCredentials {
        val accessToken = body.string("access_token")
        val refreshToken = body.string("refresh_token") ?: previous?.refreshToken
        val tokenType = body.string("token_type")
        val expiresIn = body.positiveNumber("expires_in")?.toLong()
        val scopes = body.string("scope")?.split(Regex("\\s+")).orEmpty()
        if (
            accessToken == null ||
                refreshToken.isNullOrBlank() ||
                (tokenType != null && !tokenType.equals("bearer", ignoreCase = true)) ||
                expiresIn == null ||
                (scopes.isNotEmpty() && SCOPE !in scopes)
        ) {
            throw MiniMaxCodeAuthException("MiniMax Code returned an unusable login.")
        }
        return OAuthCredentials(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAt = nowMs() + expiresIn * 1000,
            accountId = previous?.accountId,
            issuer = hosts(region).account.toString(),
        )
    }

    private fun accountExpirySeconds(value: Long): Long? {
        if (value < 1_000_000_000_000L) return value
        val remainingMs = value - nowMs()
        return if (remainingMs > 0) (remainingMs + 999) / 1000 else null
    }

    private class FormResponse(val status: Int, val json: JsonObject?)

    // Token bodies never reach logs, exceptions, or response viewers.
    private fun postForm(uri: URI, values: Map<String, String>): FormResponse {
        val request =
            HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(OAuthUrlCodec.formEncode(values)))
                .build()
        val response =
            try {
                httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw MiniMaxCodeAuthException("MiniMax Code login was interrupted.")
            } catch (exception: IOException) {
                throw MiniMaxCodeAuthException(
                    "Could not reach MiniMax Code. Check your connection.",
                )
            }
        val json = runCatching {
            JsonSupport.json.parseToJsonElement(response.body()) as? JsonObject
        }
            .getOrNull()
        return FormResponse(response.statusCode(), json)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
            ?.trim()
            ?.ifEmpty { null }

    private fun JsonObject.positiveNumber(key: String): Double? =
        (this[key] as? JsonPrimitive)
            ?.takeUnless { it.isString }
            ?.let { it.longOrNull?.toDouble() ?: it.doubleOrNull }
            ?.takeIf { it.isFinite() && it > 0 }

    companion object {
        const val CLIENT_ID = "mcode-public"
        const val SCOPE = "agent.default"
        const val AUDIENCE = "agent-backend"
        private const val DEVICE_PATH = "/oauth2/device/code"
        private const val TOKEN_PATH = "/oauth2/token"
        private const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        private val random = SecureRandom()

        private fun codeVerifier(): String {
            val bytes = ByteArray(32)
            random.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        internal fun codeChallenge(verifier: String): String =
            Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                    MessageDigest.getInstance("SHA-256")
                        .digest(verifier.toByteArray(Charsets.US_ASCII)),
                )
    }
}
