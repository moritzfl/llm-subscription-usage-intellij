package de.moritzf.quota.opencode

import de.moritzf.quota.idea.auth.OAuthCredentials
import de.moritzf.quota.shared.JsonSupport
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Console device authorization, matching OpenCode's native account login. */
open class OpenCodeOAuthClient(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val endpoint: URI = OpenCodeQuotaClient.DEFAULT_ENDPOINT,
) {
    open fun requestDeviceAuthorization(): OpenCodeDeviceAuthorization {
        val response = post("auth/device/code", JsonSupport.json.encodeToString(DeviceRequest("opencode-cli", true)))
        checkStatus(response)
        return decode<OpenCodeDeviceAuthorization>(response.body()).also {
            require(it.deviceCode.isNotBlank() && it.expiresInSeconds > 0) { "Invalid OpenCode device authorization" }
            verificationUrl(it)
        }
    }

    fun verificationUrl(authorization: OpenCodeDeviceAuthorization): String {
        require(authorization.verificationUriComplete.isNotBlank()) { "Missing OpenCode verification URL" }
        val uri = endpoint.resolve(authorization.verificationUriComplete)
        require(uri.scheme == "https" || uri.scheme == "http") { "Invalid OpenCode verification URL" }
        return uri.toString()
    }

    open fun pollDeviceToken(deviceCode: String): OpenCodeDeviceTokenResult {
        val response = post("auth/device/token", JsonSupport.json.encodeToString(
            TokenRequest("opencode-cli", "urn:ietf:params:oauth:grant-type:device_code", deviceCode = deviceCode),
        ))
        val error = errorCode(response.body())
        if (response.statusCode() in listOf(200, 400)) {
            when (error) {
                "authorization_pending" -> return OpenCodeDeviceTokenResult.Pending
                "slow_down" -> return OpenCodeDeviceTokenResult.SlowDown
            }
        }
        checkStatus(response)
        if (error != null) throw authError(response.statusCode(), error)
        return OpenCodeDeviceTokenResult.Authorized(decode<TokenResponse>(response.body()).credentials())
    }

    open fun refreshCredentials(existing: OAuthCredentials): OAuthCredentials {
        require(!existing.refreshToken.isNullOrBlank()) { "OpenCode login expired. Sign in again." }
        val response = post("auth/device/token", JsonSupport.json.encodeToString(
            TokenRequest("opencode-cli", "refresh_token", refreshToken = existing.refreshToken),
        ))
        checkStatus(response)
        return decode<TokenResponse>(response.body()).credentials(existing.accountId)
    }

    private fun post(path: String, body: String): HttpResponse<String> = httpClient.send(
        HttpRequest.newBuilder(endpoint.resolve(path))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun checkStatus(response: HttpResponse<String>) {
        if (response.statusCode() !in 200..299) throw authError(response.statusCode(), errorCode(response.body()))
    }

    private fun errorCode(body: String): String? = runCatching {
        ((JsonSupport.json.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonPrimitive)?.content
    }.getOrNull()

    // Token responses and error bodies must never enter logs or quota response viewers.
    private fun authError(status: Int, code: String?) = OpenCodeQuotaException(when (code) {
        "access_denied" -> "OpenCode login was denied."
        "expired_token" -> "OpenCode login timed out. Try again."
        "invalid_grant" -> "OpenCode login expired. Sign in again."
        else -> "OpenCode authorization failed (HTTP $status). Try signing in again."
    }, status)

    private inline fun <reified T> decode(body: String): T = try {
        JsonSupport.json.decodeFromString<T>(body)
    } catch (_: Exception) {
        throw OpenCodeQuotaException("Invalid OpenCode authorization response", 200)
    }

    @Serializable
    private data class DeviceRequest(
        @SerialName("client_id") val clientId: String,
        @SerialName("supports_org_scope") val supportsOrgScope: Boolean,
    )

    @Serializable
    private data class TokenRequest(
        @SerialName("client_id") val clientId: String,
        @SerialName("grant_type") val grantType: String,
        @SerialName("device_code") val deviceCode: String? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
    )

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String,
        @SerialName("expires_in") val expiresInSeconds: Long,
        @SerialName("org_id") val orgId: String? = null,
    ) {
        fun credentials(previousOrgId: String? = null): OAuthCredentials {
            require(accessToken.isNotBlank() && refreshToken.isNotBlank() && expiresInSeconds > 0 && orgId?.isBlank() != true) {
                "Invalid OpenCode authorization response"
            }
            return OAuthCredentials(accessToken, refreshToken, System.currentTimeMillis() + expiresInSeconds * 1000, orgId ?: previousOrgId)
        }
    }
}

@Serializable
data class OpenCodeDeviceAuthorization(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri_complete") val verificationUriComplete: String,
    @SerialName("expires_in") val expiresInSeconds: Long,
    @SerialName("interval") val intervalSeconds: Long = 5,
)

sealed interface OpenCodeDeviceTokenResult {
    data class Authorized(val credentials: OAuthCredentials) : OpenCodeDeviceTokenResult
    data object Pending : OpenCodeDeviceTokenResult
    data object SlowDown : OpenCodeDeviceTokenResult
}
