package de.moritzf.quota.kimi

import de.moritzf.quota.shared.JsonSupport
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class KimiOAuthClient(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val oauthHost: String = OAUTH_HOST,
) {
    fun requestDeviceAuthorization(): KimiDeviceAuthorization {
        val response = postForm(
            "$oauthHost/api/oauth/device_authorization",
            mapOf("client_id" to CLIENT_ID),
        )
        val body = response.body()
        if (response.statusCode() !in 200..299) {
            throw KimiQuotaException("Device authorization failed (HTTP ${response.statusCode()}).", response.statusCode(), body)
        }
        val dto = try {
            JsonSupport.json.decodeFromString<KimiDeviceAuthorizationDto>(body)
        } catch (exception: Exception) {
            throw KimiQuotaException("Could not parse device authorization response.", response.statusCode(), body, exception)
        }
        return KimiDeviceAuthorization(
            userCode = dto.userCode.orEmpty(),
            deviceCode = dto.deviceCode.orEmpty(),
            verificationUri = dto.verificationUri.orEmpty(),
            verificationUriComplete = dto.verificationUriComplete.orEmpty(),
            expiresInSeconds = dto.expiresIn ?: 0,
            intervalSeconds = dto.interval ?: 5,
        )
    }

    fun pollDeviceToken(deviceCode: String): KimiDeviceTokenPollResult {
        val response = postForm(
            "$oauthHost/api/oauth/token",
            mapOf(
                "client_id" to CLIENT_ID,
                "device_code" to deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            ),
        )
        val body = response.body()
        val status = response.statusCode()
        val dto = try {
            JsonSupport.json.decodeFromString<KimiDeviceTokenDto>(body)
        } catch (exception: Exception) {
            throw KimiQuotaException("Could not parse token polling response.", status, body, exception)
        }
        if (status in 200..299 && !dto.accessToken.isNullOrBlank()) {
            return KimiDeviceTokenPollResult.Authorized(
                KimiCredentials(
                    accessToken = dto.accessToken,
                    refreshToken = dto.refreshToken.orEmpty(),
                    expiresAtEpochSeconds = dto.expiresIn?.let { Clock.System.now().epochSeconds + it.toDouble() },
                    scope = dto.scope,
                    tokenType = dto.tokenType ?: "Bearer",
                ),
            )
        }
        val error = dto.error.orEmpty()
        return when (error) {
            "authorization_pending", "slow_down" -> KimiDeviceTokenPollResult.Pending((dto.interval ?: 0).coerceAtLeast(0))
            "expired_token" -> throw KimiQuotaException("Device authorization expired.", status, body)
            else -> throw KimiQuotaException(dto.errorDescription ?: "Token polling failed (HTTP $status).", status, body)
        }
    }

    private fun postForm(url: String, parameters: Map<String, String>): HttpResponse<String> {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
        KimiDeviceHeaders.all().forEach { (key, value) -> builder.header(key, value) }
        val request = builder.POST(HttpRequest.BodyPublishers.ofString(formEncode(parameters))).build()
        return try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: IOException) {
            throw KimiQuotaException("OAuth request failed. Check your connection.", 0, null, exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw KimiQuotaException("OAuth request failed. Check your connection.", 0, null, exception)
        }
    }

    private fun formEncode(parameters: Map<String, String>): String {
        return parameters.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, Charsets.UTF_8)}=${URLEncoder.encode(value, Charsets.UTF_8)}"
        }
    }

    companion object {
        const val CLIENT_ID = "17e5f671-d194-4dfb-9706-5516cb48c098"
        private const val OAUTH_HOST = "https://auth.kimi.com"
    }
}

data class KimiDeviceAuthorization(
    val userCode: String,
    val deviceCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
)

sealed class KimiDeviceTokenPollResult {
    data class Authorized(val credentials: KimiCredentials) : KimiDeviceTokenPollResult()
    data class Pending(val nextIntervalSeconds: Long = 0) : KimiDeviceTokenPollResult()
}

@Serializable
private data class KimiDeviceAuthorizationDto(
    @SerialName("user_code") val userCode: String? = null,
    @SerialName("device_code") val deviceCode: String? = null,
    @SerialName("verification_uri") val verificationUri: String? = null,
    @SerialName("verification_uri_complete") val verificationUriComplete: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val interval: Long? = null,
)

@Serializable
private data class KimiDeviceTokenDto(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val scope: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
    val interval: Long? = null,
)
