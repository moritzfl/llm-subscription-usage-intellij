package de.moritzf.quota.minimax

import de.moritzf.quota.shared.JsonSupport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

open class MiniMaxWebSearchClient(
    private val httpClient: HttpClient = defaultHttpClient(),
    private val globalApiHost: URI = GLOBAL_API_HOST,
    private val cnApiHost: URI = CN_API_HOST,
) {
    open fun webSearch(
        apiKey: String,
        region: MiniMaxRegion,
        query: String,
        limit: Int = DEFAULT_LIMIT,
        includeContent: Boolean = false,
    ): String {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) {
            throw MiniMaxQuotaException("Search query is required.")
        }
        val token = apiKey.trim().ifBlank {
            throw MiniMaxQuotaException("MiniMax API key missing. Add a MiniMax API key in settings.")
        }
        val resultLimit = limit.coerceIn(MIN_LIMIT, MAX_LIMIT)
        val response = send(searchRequest(token, region, trimmedQuery))
        val status = response.statusCode()
        val body = response.body()
        if (status == 401 || status == 403) {
            throw MiniMaxQuotaException("Session expired. Check your MiniMax API key.", status, body)
        }
        if (status !in 200..299) {
            throw MiniMaxQuotaException("MiniMax web search failed (HTTP $status). Try again later.", status, body)
        }
        return parseSearchResponse(body, region, resultLimit, includeContent)
    }

    private fun searchRequest(apiKey: String, region: MiniMaxRegion, query: String): HttpRequest {
        val body = buildJsonObject { put("q", query) }.toString()
        return HttpRequest.newBuilder()
            .uri(apiHost(region).resolve(WEB_SEARCH_PATH))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer $apiKey")
            .header("MM-API-Source", "Minimax-MCP")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
    }

    private fun send(request: HttpRequest): HttpResponse<String> {
        return try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: IOException) {
            throw MiniMaxQuotaException("Request failed. Check your connection.", 0, null, exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw MiniMaxQuotaException("Request failed. Check your connection.", 0, null, exception)
        }
    }

    private fun parseSearchResponse(
        body: String,
        region: MiniMaxRegion,
        limit: Int,
        includeContent: Boolean,
    ): String {
        val root = try {
            JsonSupport.json.parseToJsonElement(body) as? JsonObject
        } catch (exception: Exception) {
            throw MiniMaxQuotaException("Could not parse MiniMax web search response.", 200, body, exception)
        } ?: throw MiniMaxQuotaException("Could not parse MiniMax web search response.", 200, body)

        val baseResp = root.obj("base_resp")
        val statusCode = baseResp?.int("status_code") ?: 0
        if (statusCode != 0) {
            val statusMessage = baseResp?.string("status_msg").orEmpty().ifBlank { statusCode.toString() }
            throw MiniMaxQuotaException("MiniMax web search failed: $statusMessage", statusCode, body)
        }

        val results = searchResults(root).take(limit)
        return buildJsonObject {
            put("region", region.toString())
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
            relatedSearches(root)?.let { put("related_searches", it) }
        }.toString()
    }

    private fun searchResults(root: JsonObject): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        collectSearchResult(root["organic"], results)
        collectSearchResult(root["search_results"], results)
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
        val snippet = string("snippet") ?: string("content").orEmpty()
        if (title.isBlank() && url.isBlank() && snippet.isBlank()) return null
        return SearchResult(
            title = title,
            url = url,
            snippet = snippet,
            content = string("content"),
            date = string("date") ?: string("publish_date"),
        )
    }

    private fun relatedSearches(root: JsonObject): JsonArray? = root["related_searches"] as? JsonArray

    private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

    private fun apiHost(region: MiniMaxRegion): URI {
        return when (region) {
            MiniMaxRegion.GLOBAL -> globalApiHost
            MiniMaxRegion.CN -> cnApiHost
        }
    }

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
        private const val WEB_SEARCH_PATH = "/v1/coding_plan/search"
        private val GLOBAL_API_HOST = URI.create("https://api.minimax.io")
        private val CN_API_HOST = URI.create("https://api.minimaxi.com")

        fun createDefault(): MiniMaxWebSearchClient = MiniMaxWebSearchClient()

        private fun defaultHttpClient(): HttpClient {
            return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build()
        }
    }
}
