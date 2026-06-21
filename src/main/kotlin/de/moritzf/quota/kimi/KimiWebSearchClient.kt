package de.moritzf.quota.kimi

import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.McpJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

open class KimiWebSearchClient(
    private val httpClient: HttpClient = defaultHttpClient(),
    private val searchEndpoint: URI = SEARCH_ENDPOINT,
    tokenEndpoint: URI = TOKEN_ENDPOINT,
) {
    private val credentialRefresher = KimiCredentialRefresher(httpClient, tokenEndpoint)

    open fun webSearch(
        credentials: KimiCredentials,
        query: String,
        limit: Int = DEFAULT_LIMIT,
        includeContent: Boolean = false,
    ): KimiWebSearchFetchResult {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) {
            throw KimiQuotaException("Search query is required.")
        }

        var usableCredentials = credentialRefresher.refreshIfNeeded(credentials)
        var accessToken = usableCredentials.accessToken.ifBlank {
            throw KimiQuotaException("Kimi login required. Log in from settings.")
        }

        val resultLimit = limit.coerceIn(MIN_LIMIT, MAX_LIMIT)
        var response = send(searchRequest(accessToken, trimmedQuery, resultLimit, includeContent))
        if (response.statusCode().isUnauthorized()) {
            usableCredentials = credentialRefresher.refresh(usableCredentials)
                ?: throw KimiQuotaException("Session expired. Log in to Kimi again from settings.", response.statusCode(), response.body())
            accessToken = usableCredentials.accessToken.ifBlank {
                throw KimiQuotaException("Kimi login required. Log in from settings.")
            }
            response = send(searchRequest(accessToken, trimmedQuery, resultLimit, includeContent))
        }
        val status = response.statusCode()
        val body = response.body()
        if (status == 401 || status == 403) {
            throw KimiQuotaException("Session expired. Log in to Kimi again from settings.", status, body)
        }
        if (status !in 200..299) {
            throw KimiQuotaException("Kimi web search failed (HTTP $status). Try again later.", status, body)
        }

        return KimiWebSearchFetchResult(McpJson.providerJsonOrRaw(body), usableCredentials)
    }

    private fun searchRequest(
        accessToken: String,
        query: String,
        limit: Int,
        includeContent: Boolean,
    ): HttpRequest {
        val body = JsonSupport.json.encodeToString(
            KimiSearchRequestDto(
                textQuery = query,
                limit = limit,
                enablePageCrawling = includeContent,
                timeoutSeconds = SEARCH_TIMEOUT_SECONDS,
            ),
        )

        val builder = HttpRequest.newBuilder()
            .uri(searchEndpoint)
            .timeout(Duration.ofSeconds(180))
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
        KimiDeviceHeaders.all().forEach { (key, value) -> builder.header(key, value) }
        return builder.POST(HttpRequest.BodyPublishers.ofString(body)).build()
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

    private fun Int.isUnauthorized(): Boolean = this == 401 || this == 403

    data class KimiWebSearchFetchResult(
        val body: String,
        val credentials: KimiCredentials,
    )

    companion object {
        const val DEFAULT_LIMIT = 5
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 20
        private const val SEARCH_TIMEOUT_SECONDS = 30
        private const val USER_AGENT = "KimiCLI/1.40.0"
        private val SEARCH_ENDPOINT = URI.create("https://api.kimi.com/coding/v1/search")
        private val TOKEN_ENDPOINT = URI.create("https://auth.kimi.com/api/oauth/token")

        fun createDefault(): KimiWebSearchClient = KimiWebSearchClient()

        private fun defaultHttpClient(): HttpClient {
            return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build()
        }
    }
}

@Serializable
private data class KimiSearchRequestDto(
    @SerialName("text_query") val textQuery: String,
    val limit: Int,
    @SerialName("enable_page_crawling") val enablePageCrawling: Boolean,
    @SerialName("timeout_seconds") val timeoutSeconds: Int,
)
