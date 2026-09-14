package de.moritzf.quota.idea.auth

import com.intellij.openapi.diagnostic.Logger
import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.openai.dto.OAuthTokenResponseDto
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.UnresolvedAddressException
import java.time.Duration
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Performs OAuth token exchange and refresh requests against the configured token endpoint.
 */
class OAuthTokenClient(
    private val httpClient: HttpClient,
    private val config: OAuthClientConfig,
) : OAuthTokenOperations {
    override suspend fun exchangeAuthorizationCode(
        code: String,
        codeVerifier: String,
        state: String?,
    ): OAuthCredentials {
        LOG.info("Exchanging authorization code for tokens")
        val params = linkedMapOf(
            "grant_type" to "authorization_code",
            "client_id" to config.clientId,
            "code" to code,
            "redirect_uri" to config.redirectUri,
            "code_verifier" to codeVerifier,
        )
        if (config.includeStateInCodeExchange && !state.isNullOrBlank()) {
            params["state"] = state
        }
        config.clientSecret?.let { params["client_secret"] = it }

        val response = postToken(params)
        if (response.statusCode() !in 200..299) {
            LOG.warn("Token exchange failed: ${response.statusCode()}")
            throw createRequestException("Token exchange failed", response)
        }

        val tokenResponse = parseResponse(response.body())
        if (tokenResponse.refreshToken.isNullOrBlank()) {
            throw IOException("Token exchange did not return a refresh token")
        }

        return createCredentials(tokenResponse).also {
            it.refreshToken = tokenResponse.refreshToken
        }
    }

    override suspend fun refreshCredentials(existing: OAuthCredentials): OAuthCredentials {
        LOG.info(
            "Refreshing OAuth token (access=${QuotaTokenUtil.fingerprint(existing.accessToken)}," +
                " refresh=${QuotaTokenUtil.fingerprint(existing.refreshToken)})"
        )
        val params = linkedMapOf(
            "grant_type" to "refresh_token",
            "client_id" to config.clientId,
            "refresh_token" to existing.refreshToken.orEmpty(),
        )
        config.clientSecret?.let { params["client_secret"] = it }
        val response = postTokenWithRetry(params)
        if (response.statusCode() !in 200..299) {
            val exception = createRequestException("Token refresh failed", response)
            LOG.warn(
                "Token refresh failed: HTTP ${response.statusCode()} oauthError=${exception.oauthError ?: "none"}" +
                    " terminal=${exception.isTerminalAuthFailure()}"
            )
            throw exception
        }

        val tokenResponse = parseResponse(response.body())
        return createCredentials(tokenResponse).also { credentials ->
            credentials.refreshToken = tokenResponse.refreshToken?.takeUnless { it.isBlank() } ?: existing.refreshToken
            if (credentials.accountId == null) {
                credentials.accountId = existing.accountId
            }
            if (credentials.hd == null) {
                credentials.hd = existing.hd
            }
            LOG.info(
                "OAuth token refreshed (access=${QuotaTokenUtil.fingerprint(credentials.accessToken)}," +
                    " refresh=${QuotaTokenUtil.fingerprint(credentials.refreshToken)}," +
                    " rotated=${credentials.refreshToken != existing.refreshToken}," +
                    " expiresInMs=${credentials.expiresAt - System.currentTimeMillis()})"
            )
        }
    }

    /**
     * Retries only failures that prove the request never reached the token endpoint. Once a request
     * may have been sent, neither a connection failure nor a 5xx response proves that a rotating
     * refresh token is still usable.
     */
    private suspend fun postTokenWithRetry(parameters: Map<String, String>): HttpResponse<String> {
        for (attempt in 0 until MAX_REFRESH_ATTEMPTS) {
            val lastAttempt = attempt == MAX_REFRESH_ATTEMPTS - 1
            if (attempt > 0) {
                delay(RETRY_BASE_DELAY_MS shl (attempt - 1))
            }
            try {
                return postToken(parameters)
            } catch (exception: IOException) {
                if (lastAttempt || !isPreSendFailure(exception)) {
                    throw exception
                }
                LOG.warn("Token refresh could not connect to the token endpoint, retrying", exception)
            }
        }
        throw IOException("Token refresh failed")
    }

    private fun isPreSendFailure(exception: IOException): Boolean {
        var current: Throwable? = exception
        while (current != null) {
            if (current is HttpConnectTimeoutException ||
                current is ConnectException ||
                current is UnknownHostException ||
                current is UnresolvedAddressException
            ) {
                return true
            }
            current = current.cause?.takeIf { it !== current }
        }
        return false
    }

    private fun postToken(parameters: Map<String, String>): HttpResponse<String> {
        val (contentType, body) = when (config.tokenBodyFormat) {
            OAuthTokenBodyFormat.FORM ->
                "application/x-www-form-urlencoded" to OAuthUrlCodec.formEncode(parameters)
            OAuthTokenBodyFormat.JSON ->
                "application/json" to buildJsonObject {
                    parameters.forEach { (key, value) -> put(key, value) }
                }.toString()
        }
        val request = HttpRequest.newBuilder()
            .uri(URI.create(config.tokenEndpoint))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", contentType)
            .header("Accept", "application/json, text/plain, */*")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun createCredentials(tokenResponse: OAuthTokenResponseDto): OAuthCredentials {
        val accessToken = tokenResponse.accessToken?.takeUnless { it.isBlank() }
            ?: throw IOException("Token response did not return an access token")
        return OAuthCredentials(
            accessToken = accessToken,
            expiresAt = resolveExpiresAtMs(tokenResponse, accessToken),
            accountId = resolveAccountId(tokenResponse),
            hd = QuotaTokenUtil.extractGoogleHd(tokenResponse.idToken)
        )
    }

    private fun resolveExpiresAtMs(tokenResponse: OAuthTokenResponseDto, accessToken: String): Long {
        val now = System.currentTimeMillis()
        if (tokenResponse.expiresIn > 0) {
            return now + tokenResponse.expiresIn * 1000L
        }
        return QuotaTokenUtil.extractJwtExpiresAtMs(accessToken)
            ?: now + DEFAULT_EXPIRES_IN_MS
    }

    private fun parseResponse(body: String): OAuthTokenResponseDto {
        return JsonSupport.json.decodeFromString(body)
    }

    private fun createRequestException(prefix: String, response: HttpResponse<String>): OAuthTokenRequestException {
        val tokenResponse = runCatching { parseResponse(response.body()) }.getOrNull()
        val oauthError = tokenResponse?.error?.takeUnless { it.isBlank() }
        val description = tokenResponse?.errorDescription?.takeUnless { it.isBlank() }
        val detail = description ?: oauthError
        val message = buildString {
            append(prefix)
            append(": HTTP ")
            append(response.statusCode())
            if (!detail.isNullOrBlank()) {
                append(" ")
                append(detail)
            }
        }
        return OAuthTokenRequestException(message, response.statusCode(), oauthError)
    }

    private fun resolveAccountId(response: OAuthTokenResponseDto): String? {
        return QuotaTokenUtil.extractChatGptAccountId(response.idToken)
            ?: QuotaTokenUtil.extractChatGptAccountId(response.accessToken)
            ?: QuotaTokenUtil.extractGoogleEmail(response.idToken)
    }

    companion object {
        private val LOG = Logger.getInstance(OAuthTokenClient::class.java)
        private const val DEFAULT_EXPIRES_IN_MS = 60 * 60 * 1000L
        private const val MAX_REFRESH_ATTEMPTS = 3
        private const val RETRY_BASE_DELAY_MS = 500L
    }
}
