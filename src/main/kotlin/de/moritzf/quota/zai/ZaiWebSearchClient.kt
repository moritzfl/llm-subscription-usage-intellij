package de.moritzf.quota.zai

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

open class ZaiWebSearchClient(
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
            throw ZaiQuotaException("Search query is required.")
        }
        val token = apiKey.trim().ifBlank {
            throw ZaiQuotaException("Z.ai API key missing. Add a Z.ai API key in settings.")
        }
        val resultLimit = limit.coerceIn(MIN_LIMIT, MAX_LIMIT)
        val response = send(searchRequest(token, trimmedQuery, resultLimit, includeContent))
        val status = response.statusCode()
        val body = response.body()
        if (status == 401 || status == 403) {
            throw ZaiQuotaException("API key invalid. Check your Z.ai API key.", status, body)
        }
        if (status !in 200..299) {
            throw ZaiQuotaException("Z.ai web search failed (HTTP $status). Try again later.", status, body)
        }
        return McpJson.providerJsonOrRaw(body)
    }

    open fun webFetch(apiKey: String, url: String): String {
        val target = normalizeHttpUrl(url)
        val token = apiKey.trim().ifBlank {
            throw ZaiQuotaException("Z.ai API key missing. Add a Z.ai API key in settings.")
        }
        val response = send(fetchRequest(token, target))
        val status = response.statusCode()
        val body = response.body()
        if (status == 401 || status == 403) {
            throw ZaiQuotaException("API key invalid. Check your Z.ai API key.", status, body)
        }
        if (status !in 200..299) {
            throw ZaiQuotaException("Z.ai web fetch failed (HTTP $status). Try again later.", status, body)
        }
        return McpJson.providerJsonOrRaw(body)
    }

    private fun searchRequest(
        apiKey: String,
        query: String,
        limit: Int,
        includeContent: Boolean,
    ): HttpRequest {
        val body = JsonSupport.json.encodeToString(
            ZaiSearchRequestDto(
                searchQuery = query,
                count = limit,
                contentSize = if (includeContent) "high" else "low",
                includeImage = false,
            ),
        )

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
        val body = JsonSupport.json.encodeToString(
            ZaiReaderRequestDto(
                url = url,
                returnFormat = "markdown",
                retainImages = false,
                keepImgDataUrl = false,
                withLinksSummary = true,
            ),
        )
        return HttpRequest.newBuilder()
            .uri(endpoint(WEB_READER_PATH))
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
            throw ZaiQuotaException("Request failed. Check your connection.", 0, null, exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ZaiQuotaException("Request failed. Check your connection.", 0, null, exception)
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 5
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 20
        private const val WEB_SEARCH_PATH = "/web_search"
        private const val WEB_READER_PATH = "/reader"
        private val DEFAULT_BASE_URI = URI.create("https://api.z.ai/api/paas/v4")

        internal fun normalizeHttpUrl(value: String): String {
            val trimmed = value.trim()
            if (trimmed.isBlank()) {
                throw ZaiQuotaException("URL is required.")
            }
            val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
            val uri = runCatching { URI(withScheme) }.getOrNull()
            val scheme = uri?.scheme?.lowercase()
            if (uri?.host.isNullOrBlank() || (scheme != "http" && scheme != "https")) {
                throw ZaiQuotaException("URL must be http or https.")
            }
            return withScheme
        }

        fun createDefault(): ZaiWebSearchClient = ZaiWebSearchClient()

        private fun defaultHttpClient(): HttpClient {
            return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build()
        }
    }
}

@Serializable
private data class ZaiSearchRequestDto(
    @SerialName("search_query") val searchQuery: String,
    val count: Int,
    @SerialName("content_size") val contentSize: String,
    @SerialName("include_image") val includeImage: Boolean,
)

@Serializable
private data class ZaiReaderRequestDto(
    val url: String,
    @SerialName("return_format") val returnFormat: String,
    @SerialName("retain_images") val retainImages: Boolean,
    @SerialName("keep_img_data_url") val keepImgDataUrl: Boolean,
    @SerialName("with_links_summary") val withLinksSummary: Boolean,
)
