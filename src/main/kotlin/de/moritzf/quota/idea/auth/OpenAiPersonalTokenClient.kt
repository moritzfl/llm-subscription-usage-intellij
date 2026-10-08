package de.moritzf.quota.idea.auth

import de.moritzf.quota.shared.JsonSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Validates modern Codex personal access tokens using the same endpoint as Codex CLI. */
class OpenAiPersonalTokenClient(
    private val httpClient: HttpClient,
    private val endpoint: URI = URI.create("https://auth.openai.com/api/accounts/v1/user-auth-credential/whoami"),
) {
    suspend fun validate(input: String): OAuthCredentials {
        val token = input.trim()
        require(token.startsWith("at-") && token.length > 3 && token.all { it in '!'..'~' }) {
            "Enter a Codex personal access token starting with at- from ChatGPT workspace settings. Platform API keys and legacy agent-identity tokens are not supported here."
        }
        val response = runInterruptible(Dispatchers.IO) {
            httpClient.send(
                HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer $token").header("Accept", "application/json").GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }
        if (response.statusCode() !in 200..299) {
            throw IOException("Codex access token validation failed (HTTP ${response.statusCode()}). Check token expiration and workspace permissions.")
        }
        val metadata = try {
            JsonSupport.json.decodeFromString<PersonalTokenMetadata>(response.body())
        } catch (_: Exception) {
            throw IOException("Codex access token validation returned invalid account details")
        }
        if (metadata.accountId.isBlank()) throw IOException("Codex access token has no workspace account ID")
        if (metadata.fedramp) throw IOException("FedRAMP Codex access tokens require a separate endpoint and are not supported here.")
        return OAuthCredentials(
            accessToken = token, accountId = metadata.accountId, personalAccessToken = true,
            // The metadata endpoint does not expose expiry. The provider enforces expiry/revocation.
            expiresAt = Long.MAX_VALUE,
        )
    }
}

@Serializable
private class PersonalTokenMetadata(
    @SerialName("chatgpt_account_id") val accountId: String,
    @SerialName("chatgpt_account_is_fedramp") val fedramp: Boolean = false,
)
