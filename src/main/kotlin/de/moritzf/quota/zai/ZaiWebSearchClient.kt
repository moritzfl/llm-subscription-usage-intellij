package de.moritzf.quota.zai

import de.moritzf.quota.shared.JsonSupport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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
        return parseSearchResponse(body, resultLimit, includeContent)
    }

    private fun searchRequest(
        apiKey: String,
        query: String,
        limit: Int,
        includeContent: Boolean,
    ): HttpRequest {
        val body = buildJsonObject {
            put("search_query", query)
            put("count", limit)
            put("content_size", if (includeContent) "high" else "low")
            put("include_image", false)
        }.toString()

        return HttpRequest.newBuilder()
            .uri(baseUri.resolve(WEB_SEARCH_PATH))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
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

    private fun parseSearchResponse(body: String, limit: Int, includeContent: Boolean): String {
        val root = try {
            JsonSupport.json.parseToJsonElement(body) as? JsonObject
        } catch (exception: Exception) {
            throw ZaiQuotaException("Could not parse Z.ai web search response.", 200, body, exception)
        } ?: throw ZaiQuotaException("Could not parse Z.ai web search response.", 200, body)

        val results = searchResults(root).take(limit)
        return buildJsonObject {
            root.string("id")?.let { put("id", it) }
            root.string("request_id")?.let { put("request_id", it) }
            (root["search_intent"] as? JsonObject)?.let { put("search_intent", it) }
            putJsonArray("search_results") {
                results.forEach { result ->
                    add(buildJsonObject {
                        put("title", result.title)
                        put("url", result.url)
                        put("snippet", result.snippet)
                        result.date?.takeIf { it.isNotBlank() }?.let { put("date", it) }
                        if (includeContent) {
                            result.content?.takeIf { it.isNotBlank() }?.let { put("content", it) }
                        }
                    })
                }
            }
        }.toString()
    }

    private fun searchResults(root: JsonObject): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        collectSearchResult(root["search_result"], results)
        collectSearchResult(root["search_results"], results)
        (root["choices"] as? JsonArray)?.forEach { choice ->
            val toolCalls = (choice as? JsonObject)
                ?.obj("message")
                ?.array("tool_calls")
                ?: return@forEach
            toolCalls.forEach { call ->
                collectSearchResult((call as? JsonObject)?.get("search_result"), results)
            }
        }
        return results
    }

    private fun collectSearchResult(element: JsonElement?, results: MutableList<SearchResult>) {
        when (element) {
            is JsonArray -> element.forEach { collectSearchResult(it, results) }
            is JsonObject -> element.toSearchResult()?.let(results::add)
            else -> Unit
        }
    }

    private fun JsonObject.toSearchResult(): SearchResult? {
        val title = string("title").orEmpty()
        val url = string("link") ?: string("url") ?: ""
        val content = string("content")
        val snippet = string("snippet") ?: content.orEmpty()
        if (title.isBlank() && url.isBlank() && snippet.isBlank()) return null
        return SearchResult(
            title = title,
            url = url,
            snippet = snippet,
            content = content,
            date = string("publish_date") ?: string("date"),
        )
    }

    private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

    private fun JsonObject.array(name: String): JsonArray? = this[name] as? JsonArray

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private data class SearchResult(
        val title: String,
        val url: String,
        val snippet: String,
        val content: String?,
        val date: String?,
    )

    companion object {
        const val DEFAULT_LIMIT = 5
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 20
        private const val WEB_SEARCH_PATH = "/web_search"
        private val DEFAULT_BASE_URI = URI.create("https://api.z.ai/api/paas/v4")

        fun createDefault(): ZaiWebSearchClient = ZaiWebSearchClient()

        private fun defaultHttpClient(): HttpClient {
            return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build()
        }
    }
}
