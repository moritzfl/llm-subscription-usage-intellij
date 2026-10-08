package de.moritzf.quota.idea.auth

import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.shared.JsonSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.time.Instant

/** Public instructions only; the device grant itself never reaches the UI. */
data class DeviceLoginPrompt(val verificationUrl: String, val userCode: String, val expiresAtMs: Long)

class OAuthDeviceAuthorization(
    val prompt: DeviceLoginPrompt,
    val deviceCode: String,
    val intervalSeconds: Long,
)

sealed interface OAuthDevicePollResult {
    class Authorized(val credentials: OAuthCredentials) : OAuthDevicePollResult
    data object Pending : OAuthDevicePollResult
    data object SlowDown : OAuthDevicePollResult
}

interface OAuthDeviceLoginOperations {
    suspend fun requestAuthorization(): OAuthDeviceAuthorization
    suspend fun poll(authorization: OAuthDeviceAuthorization): OAuthDevicePollResult
}

/** Codex uses a code/PKCE exchange; xAI uses the standard device authorization grant. */
class OAuthDeviceLoginClient(
    private val httpClient: HttpClient,
    private val type: QuotaProviderType,
    private val config: OAuthClientConfig,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : OAuthDeviceLoginOperations {
    init {
        require(type == QuotaProviderType.OPEN_AI || type == QuotaProviderType.SUPERGROK)
    }

    private val issuer = URI.create(config.tokenEndpoint).resolve("/")

    override suspend fun requestAuthorization(): OAuthDeviceAuthorization {
        val codex = type == QuotaProviderType.OPEN_AI
        val response = if (codex) {
            post("/api/accounts/deviceauth/usercode", JsonSupport.json.encodeToString(CodexDeviceRequest(config.clientId)), true)
        } else {
            post("/oauth2/device/code", OAuthUrlCodec.formEncode(mapOf("client_id" to config.clientId, "scope" to config.scopes)), false)
        }
        if (response.statusCode() !in 200..299) {
            val hint = if (codex && response.statusCode() in listOf(403, 404)) {
                " Enable device-code login in ChatGPT security settings, or ask your workspace admin."
            } else ""
            throw IOException("Device-code request failed (HTTP ${response.statusCode()}).$hint")
        }
        val dto = decode<DeviceAuthorizationResponse>(response.body())
        val userCode = dto.userCode ?: dto.legacyUserCode
        val deviceCode = if (codex) dto.deviceAuthId else dto.deviceCode
        val url = if (codex) issuer.resolve("/codex/device").toString() else dto.verificationUri
        val expiresAt = if (codex) {
            dto.expiresAt?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
                ?: (nowMs() + 15 * 60_000)
        } else {
            val seconds = dto.expiresIn?.takeIf { it in 1..86_400 }
                ?: throw IOException("Device-code response has no usable expiration")
            nowMs() + seconds * 1000
        }
        if (userCode.isNullOrBlank() || deviceCode.isNullOrBlank() || url.isNullOrBlank() || expiresAt <= nowMs()) {
            throw IOException("Device-code response is missing usable login details")
        }
        val uri = runCatching { URI.create(url) }.getOrNull()
        if (uri?.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) {
            throw IOException("Device-code response has an invalid verification URL")
        }
        return OAuthDeviceAuthorization(
            DeviceLoginPrompt(url, userCode, expiresAt), deviceCode,
            (dto.interval?.content?.trim()?.toLongOrNull() ?: 5).coerceAtLeast(1),
        )
    }

    override suspend fun poll(authorization: OAuthDeviceAuthorization): OAuthDevicePollResult {
        if (type == QuotaProviderType.OPEN_AI) {
            val response = post(
                "/api/accounts/deviceauth/token",
                JsonSupport.json.encodeToString(CodexDevicePoll(authorization.deviceCode, authorization.prompt.userCode)), true,
            )
            if (response.statusCode() in listOf(403, 404)) return OAuthDevicePollResult.Pending
            if (response.statusCode() !in 200..299) throw IOException("Device-code authorization failed (HTTP ${response.statusCode()})")
            val code = decode<CodexDeviceCode>(response.body())
            if (code.authorizationCode.isBlank() || code.codeVerifier.isBlank()) throw IOException("Device authorization returned no code")
            val tokens = OAuthTokenClient(httpClient, config.copy(redirectUri = issuer.resolve("/deviceauth/callback").toString()))
                .exchangeAuthorizationCode(code.authorizationCode, code.codeVerifier)
            return OAuthDevicePollResult.Authorized(tokens)
        }
        val response = post(
            "/oauth2/token",
            OAuthUrlCodec.formEncode(linkedMapOf(
                "client_id" to config.clientId,
                "device_code" to authorization.deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            )),
            false,
        )
        val error = decode<DevicePollError>(response.body()).error
        return when (error) {
            "authorization_pending" -> OAuthDevicePollResult.Pending
            "slow_down" -> OAuthDevicePollResult.SlowDown
            "expired_token" -> throw IOException("Device code expired. Start a new login.")
            "access_denied" -> throw IOException("Device-code login was denied.")
            null -> {
                if (response.statusCode() !in 200..299) throw IOException("Device-code authorization failed (HTTP ${response.statusCode()})")
                OAuthDevicePollResult.Authorized(OAuthTokenClient(httpClient, config).readLoginCredentials(response.body()))
            }
            else -> throw IOException("Device-code authorization failed (HTTP ${response.statusCode()})")
        }
    }

    private suspend fun post(path: String, body: String, json: Boolean): HttpResponse<String> = runInterruptible(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(issuer.resolve(path))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", if (json) "application/json" else "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private inline fun <reified T> decode(body: String): T = try {
        JsonSupport.json.decodeFromString<T>(body)
    } catch (_: Exception) {
        // Serialization exceptions can contain the credential-bearing response.
        throw IOException("Could not parse device authorization response")
    }
}

@Serializable
private class CodexDeviceRequest(@SerialName("client_id") val clientId: String)

@Serializable
private class CodexDevicePoll(
    @SerialName("device_auth_id") val deviceAuthId: String,
    @SerialName("user_code") val userCode: String,
)

@Serializable
private class CodexDeviceCode(
    @SerialName("authorization_code") val authorizationCode: String,
    @SerialName("code_verifier") val codeVerifier: String,
)

@Serializable
private class DevicePollError(val error: String? = null)

@Serializable
private class DeviceAuthorizationResponse(
    @SerialName("device_auth_id") val deviceAuthId: String? = null,
    @SerialName("device_code") val deviceCode: String? = null,
    @SerialName("user_code") val userCode: String? = null,
    @SerialName("usercode") val legacyUserCode: String? = null,
    @SerialName("verification_uri") val verificationUri: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    val interval: JsonPrimitive? = null,
)
