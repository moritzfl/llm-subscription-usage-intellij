package de.moritzf.quota.openai

import de.moritzf.quota.shared.JsonSupport
import kotlinx.datetime.Clock
import java.io.IOException
import java.time.Duration
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.serialization.SerializationException
import java.util.UUID

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
            throw OpenAiCodexQuotaException("Usage request failed: $status $body", status, body)
        }

        val quota = try {
            JsonSupport.json.decodeFromString<OpenAiCodexQuota>(body)
        } catch (exception: SerializationException) {
            throw OpenAiCodexQuotaException("Usage response could not be parsed", status, body, exception)
        } catch (exception: IllegalArgumentException) {
            throw OpenAiCodexQuotaException("Usage response could not be parsed", status, body, exception)
        }
        if (!quota.hasUsageState()) {
            throw OpenAiCodexQuotaException("Usage response did not include usable quota state", status, body)
        }

        quota.fetchedAt = Clock.System.now()
        quota.rawJson = body
        val resetCredits = fetchResetCredits(accessToken, accountId)
        val resetCreditsAvailableCount = resetCredits.effectiveAvailableCount()
        if (resetCreditsAvailableCount > quota.resetCreditsAvailableCount) {
            quota.resetCreditsAvailableCount = resetCreditsAvailableCount
        }
        if (resetCredits.credits.isNotEmpty()) {
            quota.resetCredits = resetCredits.credits
        }
        return quota
    }

    @Throws(IOException::class, InterruptedException::class)
    fun consumeResetCredit(accessToken: String, accountId: String?, creditId: String?): ConsumeRateLimitResetCreditResponse {
        require(accessToken.isNotBlank()) { "accessToken must not be null or blank" }

        val body = JsonSupport.json.encodeToString(
            ConsumeRateLimitResetCreditRequest(
                creditId = creditId,
                redeemRequestId = UUID.randomUUID().toString(),
            ),
        )
        val requestBuilder = HttpRequest.newBuilder()
            .uri(resetConsumeEndpoint)
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))

        if (!accountId.isNullOrBlank()) {
            requestBuilder.header("ChatGPT-Account-Id", accountId.trim())
        }

        val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
        val status = response.statusCode()
        val responseBody = response.body()
        if (status !in 200..299) {
            throw OpenAiCodexQuotaException("Reset request failed: $status $responseBody", status, responseBody)
        }
        return try {
            JsonSupport.json.decodeFromString<ConsumeRateLimitResetCreditResponse>(responseBody)
        } catch (exception: SerializationException) {
            throw OpenAiCodexQuotaException("Reset response could not be parsed", status, responseBody, exception)
        } catch (exception: IllegalArgumentException) {
            throw OpenAiCodexQuotaException("Reset response could not be parsed", status, responseBody, exception)
        }
    }

    private fun fetchResetCredits(accessToken: String, accountId: String?): RateLimitResetCreditsResponse {
        val requestBuilder = HttpRequest.newBuilder()
            .uri(resetCreditsEndpoint)
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
            return RateLimitResetCreditsResponse()
        }
        return runCatching {
            JsonSupport.json.decodeFromString<RateLimitResetCreditsResponse>(body)
        }.getOrDefault(RateLimitResetCreditsResponse())
    }

    companion object {
        @JvmField
        val DEFAULT_ENDPOINT: URI = URI.create("https://chatgpt.com/backend-api/wham/usage")

        private val DEFAULT_RESET_CREDITS_ENDPOINT: URI =
            URI.create("https://chatgpt.com/backend-api/wham/rate-limit-reset-credits")

        private val DEFAULT_RESET_CONSUME_ENDPOINT: URI =
            URI.create("https://chatgpt.com/backend-api/wham/rate-limit-reset-credits/consume")
    }

    private val resetCreditsEndpoint: URI = endpoint.resolve(DEFAULT_RESET_CREDITS_ENDPOINT.path)
    private val resetConsumeEndpoint: URI = endpoint.resolve(DEFAULT_RESET_CONSUME_ENDPOINT.path)
}
