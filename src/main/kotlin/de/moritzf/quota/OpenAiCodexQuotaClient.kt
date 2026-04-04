package de.moritzf.quota

import kotlinx.datetime.Clock
import java.io.IOException
import java.time.Duration
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * HTTP client for fetching and parsing OpenAI Codex usage quota responses.
 */
class OpenAiCodexQuotaClient(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val endpoint: URI = DEFAULT_ENDPOINT,
) {
    @Throws(IOException::class, InterruptedException::class)
    fun fetchQuota(accessToken: String, accountId: String?): OpenAiCodexQuota {
        require(accessToken.isNotBlank()) { "accessToken must not be null or blank" }

        val requestBuilder = HttpRequest.newBuilder()
            .uri(endpoint)
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .GET()

        if (!accountId.isNullOrBlank()) {
            requestBuilder.header("ChatGPT-Account-Id", accountId.trim())
        }

        val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
        val status = response.statusCode()
        val body = response.body()

        if (status !in 200..299) {
            throw OpenAiCodexQuotaException("Usage request failed: $status $body", status)
        }

        val quota = JsonSupport.json.decodeFromString<OpenAiCodexQuota>(body)
        if (!quota.hasUsableWindows()) {
            throw OpenAiCodexQuotaException("Usage response did not include usable windows", 200)
        }

        quota.fetchedAt = Clock.System.now()
        quota.rawJson = body
        return quota
    }

    companion object {
        @JvmField
        val DEFAULT_ENDPOINT: URI = URI.create("https://chatgpt.com/backend-api/wham/usage")
    }
}
