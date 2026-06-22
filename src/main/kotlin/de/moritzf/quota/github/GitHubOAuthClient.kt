package de.moritzf.quota.github

import de.moritzf.quota.idea.auth.OAuthUrlCodec
import de.moritzf.quota.shared.JsonSupport
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * GitHub OAuth 2.0 Device Authorization Grant (RFC 8628) against github.com.
 * Unlike browser-redirect OAuth, the user enters [GitHubDeviceAuthorization.userCode]
 * at the verification URL; the device-flow token does not expire and there is no
 * refresh token.
 */
class GitHubOAuthClient(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val deviceCodeEndpoint: URI = DEVICE_CODE_ENDPOINT,
    private val accessTokenEndpoint: URI = ACCESS_TOKEN_ENDPOINT,
    private val defaultVerificationUri: String = DEFAULT_VERIFICATION_URI,
) {
    fun requestDeviceAuthorization(): GitHubDeviceAuthorization {
        val response = postForm(
            deviceCodeEndpoint,
            "client_id" to CLIENT_ID,
            "scope" to SCOPE,
        )
        val body = response.body()
        if (response.statusCode() !in 200..299) {
            throw GitHubQuotaException("Device authorization failed (HTTP ${response.statusCode()}).", response.statusCode(), body)
        }
        val dto = try {
            JsonSupport.json.decodeFromString<GitHubDeviceAuthorizationDto>(body)
        } catch (exception: Exception) {
            throw GitHubQuotaException("Could not parse device authorization response.", response.statusCode(), body, exception)
        }
        return GitHubDeviceAuthorization(
            userCode = dto.userCode.orEmpty(),
            deviceCode = dto.deviceCode.orEmpty(),
            verificationUri = dto.verificationUri ?: defaultVerificationUri,
            expiresInSeconds = dto.expiresIn ?: 900,
            intervalSeconds = dto.interval ?: 5,
        )
    }

    fun pollDeviceToken(deviceCode: String): GitHubDeviceTokenPollResult {
        val response = postForm(
            accessTokenEndpoint,
            "client_id" to CLIENT_ID,
            "device_code" to deviceCode,
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
        )
        val body = response.body()
        val status = response.statusCode()
        val dto = try {
            JsonSupport.json.decodeFromString<GitHubDeviceTokenDto>(body)
        } catch (exception: Exception) {
            throw GitHubQuotaException("Could not parse token polling response.", status, body, exception)
        }
        if (status in 200..299 && !dto.accessToken.isNullOrBlank()) {
            return GitHubDeviceTokenPollResult.Authorized(
                GitHubCredentials(accessToken = dto.accessToken, oauthClientId = CLIENT_ID),
            )
        }
        return when (dto.error) {
            "authorization_pending" -> GitHubDeviceTokenPollResult.Pending(0)
            // RFC 8628 §3.5: slow_down means add 5 seconds to the current interval;
            // GitHub may also return the new interval explicitly.
            "slow_down" -> GitHubDeviceTokenPollResult.Pending(dto.interval?.toInt() ?: 0, slowDown = true)
            "expired_token" -> throw GitHubQuotaException("The device code expired. Start the login again.", status, body)
            "access_denied" -> throw GitHubQuotaException("GitHub login was denied.", status, body)
            else -> throw GitHubQuotaException(
                dto.errorDescription ?: dto.error ?: "GitHub login failed (HTTP $status).",
                status,
                body,
            )
        }
    }

    private fun postForm(endpoint: URI, vararg pairs: Pair<String, String>): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(endpoint)
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .POST(HttpRequest.BodyPublishers.ofString(OAuthUrlCodec.formEncode(*pairs)))
            .build()
        return try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: IOException) {
            throw GitHubQuotaException("Request failed. Check your connection.", 0, null, exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw GitHubQuotaException("Request failed. Check your connection.", 0, null, exception)
        }
    }

    companion object {
        private val DEVICE_CODE_ENDPOINT = deviceCodeEndpoint(null)
        private val ACCESS_TOKEN_ENDPOINT = accessTokenEndpoint(null)
        private const val DEFAULT_VERIFICATION_URI = "https://github.com/login/device"

        /** Public GitHub Copilot CLI/opencode device-flow client id; device flow needs no secret. */
        internal const val CLIENT_ID = "Ov23li8tweQw6odWQebz"

        /** `read:user` is sufficient for `copilot_internal/user`. */
        private const val SCOPE = "read:user"

        internal const val USER_AGENT = "openai-usage-quota-intellij"

        fun forHost(enterpriseHost: String?): GitHubOAuthClient {
            val host = GitHubQuotaClient.normalizedEnterpriseHost(enterpriseHost)
            return GitHubOAuthClient(
                deviceCodeEndpoint = deviceCodeEndpoint(host),
                accessTokenEndpoint = accessTokenEndpoint(host),
                defaultVerificationUri = "https://$host/login/device",
            )
        }

        fun deviceCodeEndpoint(enterpriseHost: String?): URI {
            return URI.create("https://${GitHubQuotaClient.normalizedEnterpriseHost(enterpriseHost)}/login/device/code")
        }

        fun accessTokenEndpoint(enterpriseHost: String?): URI {
            return URI.create("https://${GitHubQuotaClient.normalizedEnterpriseHost(enterpriseHost)}/login/oauth/access_token")
        }
    }
}

data class GitHubDeviceAuthorization(
    val userCode: String,
    val deviceCode: String,
    val verificationUri: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int,
)

sealed interface GitHubDeviceTokenPollResult {
    data class Authorized(val credentials: GitHubCredentials) : GitHubDeviceTokenPollResult

    /**
     * [nextIntervalSeconds] is 0 when the server did not dictate a new interval;
     * with [slowDown] the caller must then add 5 seconds to its current interval.
     */
    data class Pending(val nextIntervalSeconds: Int, val slowDown: Boolean = false) : GitHubDeviceTokenPollResult
}

@Serializable
private data class GitHubDeviceAuthorizationDto(
    @SerialName("device_code") val deviceCode: String? = null,
    @SerialName("user_code") val userCode: String? = null,
    @SerialName("verification_uri") val verificationUri: String? = null,
    @SerialName("expires_in") val expiresIn: Int? = null,
    val interval: Int? = null,
)

@Serializable
private data class GitHubDeviceTokenDto(
    @SerialName("access_token") val accessToken: String? = null,
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
    val interval: Long? = null,
)
