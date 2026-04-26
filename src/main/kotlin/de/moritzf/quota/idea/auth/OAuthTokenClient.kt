package de.moritzf.quota.idea.auth

import com.intellij.openapi.diagnostic.Logger
import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.openai.dto.OAuthTokenResponseDto
import de.moritzf.quota.idea.auth.QuotaTokenUtil
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Performs OAuth token exchange and refresh requests against the configured token endpoint.
 */
class OAuthTokenClient(
    private val httpClient: HttpClient,
    private val config: OAuthClientConfig,
) : OAuthTokenOperations {
    override suspend fun exchangeAuthorizationCode(code: String, codeVerifier: String): OAuthCredentials {
        LOG.info("Exchanging authorization code for tokens")
        val response = postForm(
            linkedMapOf(
                "grant_type" to "authorization_code",
                "client_id" to config.clientId,
                "code" to code,
                "redirect_uri" to config.redirectUri,
                "code_verifier" to codeVerifier,
            ),
        )
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
        LOG.info("Refreshing OAuth token")
        val response = postForm(
            linkedMapOf(
                "grant_type" to "refresh_token",
                "client_id" to config.clientId,
                "refresh_token" to existing.refreshToken.orEmpty(),
            ),
        )
        if (response.statusCode() !in 200..299) {
            LOG.warn("Token refresh failed: ${response.statusCode()}")
            throw createRequestException("Token refresh failed", response)
        }

        val tokenResponse = parseResponse(response.body())
        return createCredentials(tokenResponse).also { credentials ->
            credentials.refreshToken = tokenResponse.refreshToken?.takeUnless { it.isBlank() } ?: existing.refreshToken
            if (credentials.accountId == null) {
                credentials.accountId = existing.accountId
            }
        }
    }

    private fun postForm(parameters: Map<String, String>): HttpResponse<String> {
        val body = formUrlEncode(parameters)
        val request = HttpRequest.newBuilder()
            .uri(URI.create(config.tokenEndpoint))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun createCredentials(tokenResponse: OAuthTokenResponseDto): OAuthCredentials {
        val accessToken = tokenResponse.accessToken?.takeUnless { it.isBlank() }
            ?: throw IOException("Token response did not return an access token")
        return OAuthCredentials(
            accessToken = accessToken,
            expiresAt = System.currentTimeMillis() + tokenResponse.expiresIn * 1000L,
            accountId = resolveAccountId(tokenResponse),
        )
    }

    private fun parseResponse(body: String): OAuthTokenResponseDto {
        return JsonSupport.json.decodeFromString(body)
    }

    private fun createRequestException(prefix: String, response: HttpResponse<String>): OAuthTokenRequestException {
        val tokenResponse = runCatching { parseResponse(response.body()) }.getOrNull()
        val oauthError = tokenResponse?.error?.takeUnless { it.isBlank() }
        val description = tokenResponse?.errorDescription?.takeUnless { it.isBlank() }
        val fallbackBody = response.body().takeUnless { it.isBlank() }
        val detail = description ?: oauthError ?: fallbackBody
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
    }

    private fun formUrlEncode(parameters: Map<String, String>): String {
        return parameters.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
    }

    companion object {
        private val LOG = Logger.getInstance(OAuthTokenClient::class.java)
    }
}
