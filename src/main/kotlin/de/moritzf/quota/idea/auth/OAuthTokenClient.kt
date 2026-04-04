package de.moritzf.quota.idea.auth

import com.intellij.openapi.diagnostic.Logger
import de.moritzf.quota.JsonSupport
import de.moritzf.quota.dto.OAuthTokenResponseDto
import de.moritzf.quota.idea.QuotaTokenUtil
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import java.io.IOException

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
            Parameters.build {
                append("grant_type", "authorization_code")
                append("client_id", config.clientId)
                append("code", code)
                append("redirect_uri", config.redirectUri)
                append("code_verifier", codeVerifier)
            },
        )
        if (!response.status.isSuccess()) {
            LOG.warn("Token exchange failed: ${response.status.value}")
            throw IOException("Token exchange failed: ${response.status.value} ${response.bodyAsText()}")
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
            Parameters.build {
                append("grant_type", "refresh_token")
                append("client_id", config.clientId)
                append("refresh_token", existing.refreshToken.orEmpty())
            },
        )
        if (!response.status.isSuccess()) {
            LOG.warn("Token refresh failed: ${response.status.value}")
            throw IOException("Token refresh failed: HTTP ${response.status.value}")
        }

        val tokenResponse = parseResponse(response.body())
        return createCredentials(tokenResponse).also { credentials ->
            credentials.refreshToken = tokenResponse.refreshToken?.takeUnless { it.isBlank() } ?: existing.refreshToken
            if (credentials.accountId == null) {
                credentials.accountId = existing.accountId
            }
        }
    }

    private suspend fun postForm(parameters: Parameters) = httpClient.post(config.tokenEndpoint) {
        setBody(FormDataContent(parameters))
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

    private fun resolveAccountId(response: OAuthTokenResponseDto): String? {
        return QuotaTokenUtil.extractChatGptAccountId(response.idToken)
            ?: QuotaTokenUtil.extractChatGptAccountId(response.accessToken)
    }

    companion object {
        private val LOG = Logger.getInstance(OAuthTokenClient::class.java)
    }
}
