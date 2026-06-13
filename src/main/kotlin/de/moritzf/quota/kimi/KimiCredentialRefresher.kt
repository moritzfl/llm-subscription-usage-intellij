package de.moritzf.quota.kimi

import de.moritzf.quota.idea.auth.OAuthUrlCodec
import de.moritzf.quota.shared.JsonSupport
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal class KimiCredentialRefresher(
    private val httpClient: HttpClient,
    private val tokenEndpoint: URI = TOKEN_ENDPOINT,
) {
    fun refreshIfNeeded(credentials: KimiCredentials): KimiCredentials {
        val expiresAt = credentials.expiresAtEpochSeconds
        if (credentials.accessToken.isNotBlank() && (expiresAt == null || expiresAt - Clock.System.now().epochSeconds > REFRESH_BUFFER_SECONDS)) {
            return credentials
        }
        val refreshToken = credentials.refreshToken.ifBlank { return credentials }
        return refreshCredentials(refreshToken)
    }

    private fun refreshCredentials(refreshToken: String): KimiCredentials {
        val form = OAuthUrlCodec.formEncode(
            "client_id" to CLIENT_ID,
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
        )
        val builder = HttpRequest.newBuilder()
            .uri(tokenEndpoint)
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
        KimiDeviceHeaders.all().forEach { (key, value) -> builder.header(key, value) }
        val request = builder.POST(HttpRequest.BodyPublishers.ofString(form)).build()
        val response = send(request)
        val status = response.statusCode()
        val body = response.body()
        if (status == 401 || status == 403) {
            throw KimiQuotaException("Session expired. Log in to Kimi again from settings.", status, body)
        }
        if (status !in 200..299) {
            throw KimiQuotaException("Token refresh failed (HTTP $status). Log in to Kimi again.", status, body)
        }
        val dto = try {
            JsonSupport.json.decodeFromString<KimiTokenResponseDto>(body)
        } catch (exception: Exception) {
            throw KimiQuotaException("Could not parse Kimi token response.", status, body, exception)
        }
        return KimiCredentials(
            accessToken = dto.accessToken.orEmpty(),
            refreshToken = dto.refreshToken ?: refreshToken,
            expiresAtEpochSeconds = dto.expiresIn?.let { Clock.System.now().epochSeconds + it.toDouble() },
            scope = dto.scope,
            tokenType = dto.tokenType ?: "Bearer",
        )
    }

    private fun send(request: HttpRequest): HttpResponse<String> {
        return try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: IOException) {
            throw KimiQuotaException("Request failed. Check your connection.", 0, null, exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw KimiQuotaException("Request failed. Check your connection.", 0, null, exception)
        }
    }

    private companion object {
        val TOKEN_ENDPOINT: URI = URI.create("https://auth.kimi.com/api/oauth/token")
        const val CLIENT_ID = "17e5f671-d194-4dfb-9706-5516cb48c098"
        const val REFRESH_BUFFER_SECONDS = 300L
    }
}

@Serializable
private data class KimiTokenResponseDto(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val scope: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
)
