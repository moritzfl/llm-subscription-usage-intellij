package de.moritzf.quota.kimi

import de.moritzf.quota.shared.JsonSupport
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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

        val usableCredentials = credentialRefresher.refreshIfNeeded(credentials)
        val accessToken = usableCredentials.accessToken.ifBlank {
            throw KimiQuotaException("Kimi login required. Log in from settings.")
        }

        val resultLimit = limit.coerceIn(MIN_LIMIT, MAX_LIMIT)
        val request = searchRequest(accessToken, trimmedQuery, resultLimit, includeContent)
        val response = send(request)
        val status = response.statusCode()
        val body = response.body()
        if (status == 401 || status == 403) {
            throw KimiQuotaException("Session expired. Log in to Kimi again from settings.", status, body)
        }
        if (status !in 200..299) {
            throw KimiQuotaException("Kimi web search failed (HTTP $status). Try again later.", status, body)
        }

        return KimiWebSearchFetchResult(parseSearchResponse(body, resultLimit, includeContent), usableCredentials)
    }

    private fun searchRequest(
        accessToken: String,
        query: String,
        limit: Int,
        includeContent: Boolean,
    ): HttpRequest {
        val body = buildJsonObject {
            put("text_query", query)
            put("limit", limit)
            put("enable_page_crawling", includeContent)
            put("timeout_seconds", SEARCH_TIMEOUT_SECONDS)
        }.toString()

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

    private fun parseSearchResponse(body: String, limit: Int, includeContent: Boolean): String {
        val dto = try {
            JsonSupport.json.decodeFromString<KimiSearchResponseDto>(body)
        } catch (exception: Exception) {
            throw KimiQuotaException("Could not parse Kimi web search response.", 200, body, exception)
        }
        return buildJsonObject {
            putJsonArray("search_results") {
                dto.searchResults.take(limit).forEach { result ->
                    add(buildJsonObject {
                        put("title", result.title.orEmpty())
                        put("url", result.url.orEmpty())
                        put("snippet", result.snippet.orEmpty())
                        result.date?.takeIf { it.isNotBlank() }?.let { put("date", it) }
                        if (includeContent) {
                            result.content?.takeIf { it.isNotBlank() }?.let { put("content", it) }
                        }
                    })
                }
            }
        }.toString()
    }

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
private data class KimiSearchResponseDto(
    @SerialName("search_results") val searchResults: List<KimiSearchResultDto> = emptyList(),
)

@Serializable
private data class KimiSearchResultDto(
    val title: String? = null,
    val url: String? = null,
    val snippet: String? = null,
    val content: String? = null,
    val date: String? = null,
)
