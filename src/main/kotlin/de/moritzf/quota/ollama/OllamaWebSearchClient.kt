package de.moritzf.quota.ollama

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

open class OllamaWebSearchClient(
    private val httpClient: HttpClient = defaultHttpClient(),
    private val baseUri: URI = DEFAULT_BASE_URI,
) {
    open fun webSearch(
        apiKey: String,
        query: String,
        limit: Int = DEFAULT_LIMIT,
        includeContent: Boolean = false,
    ): String {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) {
            throw OllamaQuotaException("Search query is required.")
        }
        val token = apiKey.trim().ifBlank {
            throw OllamaQuotaException("Ollama API key missing. Add an Ollama API key in settings.")
        }
        val resultLimit = limit.coerceIn(MIN_LIMIT, MAX_LIMIT)
        val response = send(searchRequest(token, trimmedQuery, resultLimit))
        val status = response.statusCode()
        val body = response.body()
        if (status == 401 || status == 403) {
            throw OllamaQuotaException("Ollama API key invalid. Check your Ollama API key.", status, body)
        }
        if (status !in 200..299) {
            throw OllamaQuotaException("Ollama web search failed (HTTP $status). Try again later.", status, body)
        }
        return McpJson.providerJsonOrRaw(body)
    }

    open fun webFetch(apiKey: String, url: String): String {
        val target = normalizeHttpUrl(url)
        val token = apiKey.trim().ifBlank {
            throw OllamaQuotaException("Ollama API key missing. Add an Ollama API key in settings.")
        }
        val response = send(fetchRequest(token, target))
        val status = response.statusCode()
        val body = response.body()
        if (status == 401 || status == 403) {
            throw OllamaQuotaException("Ollama API key invalid. Check your Ollama API key.", status, body)
        }
        if (status !in 200..299) {
            throw OllamaQuotaException("Ollama web fetch failed (HTTP $status). Try again later.", status, body)
        }
        return McpJson.providerJsonOrRaw(body)
    }

    private fun searchRequest(apiKey: String, query: String, limit: Int): HttpRequest {
        val body = JsonSupport.json.encodeToString(OllamaSearchRequestDto(query, limit))

        return HttpRequest.newBuilder()
            .uri(baseUri.resolve(WEB_SEARCH_PATH))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
    }

    private fun fetchRequest(apiKey: String, url: String): HttpRequest {
        val body = JsonSupport.json.encodeToString(OllamaFetchRequestDto(url))
        return HttpRequest.newBuilder()
            .uri(endpoint(WEB_FETCH_PATH))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
    }

    private fun endpoint(path: String): URI {
        return URI.create(baseUri.toString().trimEnd('/') + path)
    }

    private fun send(request: HttpRequest): HttpResponse<String> {
        return try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: IOException) {
            throw OllamaQuotaException("Request failed. Check your connection.", 0, null, exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw OllamaQuotaException("Request failed. Check your connection.", 0, null, exception)
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 5
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 10
        private const val WEB_SEARCH_PATH = "/api/web_search"
        private const val WEB_FETCH_PATH = "/api/web_fetch"
        private val DEFAULT_BASE_URI = URI.create("https://ollama.com")

        internal fun normalizeHttpUrl(value: String): String {
            val trimmed = value.trim()
            if (trimmed.isBlank()) {
                throw OllamaQuotaException("URL is required.")
            }
            val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
            val uri = runCatching { URI(withScheme) }.getOrNull()
            val scheme = uri?.scheme?.lowercase()
            if (uri?.host.isNullOrBlank() || (scheme != "http" && scheme != "https")) {
                throw OllamaQuotaException("URL must be http or https.")
            }
            return withScheme
        }

        fun createDefault(): OllamaWebSearchClient = OllamaWebSearchClient()

        private fun defaultHttpClient(): HttpClient {
            return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build()
        }
    }
}

@Serializable
private data class OllamaSearchRequestDto(
    val query: String,
    @SerialName("max_results") val maxResults: Int,
)

@Serializable
private data class OllamaFetchRequestDto(
    val url: String,
)
