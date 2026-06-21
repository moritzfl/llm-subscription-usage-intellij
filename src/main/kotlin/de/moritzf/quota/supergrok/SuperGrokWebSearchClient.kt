package de.moritzf.quota.supergrok

import de.moritzf.quota.shared.JsonSupport
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

open class SuperGrokWebSearchClient(
    private val httpClient: HttpClient = defaultHttpClient(),
    private val baseUri: URI = DEFAULT_BASE_URI,
) {
    open fun webSearch(
        accessToken: String,
        query: String,
        model: String = DEFAULT_MODEL,
        allowedDomains: String? = null,
        excludedDomains: String? = null,
        maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
    ): String {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) {
            throw SuperGrokQuotaException("Search query is required.")
        }
        val token = accessToken.trim().ifBlank {
            throw SuperGrokQuotaException("Grok login required. Log in from SuperGrok settings.")
        }
        val trimmedModel = model.trim().ifBlank { DEFAULT_MODEL }
        val allowed = parseDomainList(allowedDomains)
        val excluded = parseDomainList(excludedDomains)
        if (allowed == null || excluded == null || (allowed.isNotEmpty() && excluded.isNotEmpty())) {
            throw SuperGrokQuotaException(
                "Invalid Grok web search options. allowedDomains and excludedDomains must be comma-separated " +
                    "domain names, up to $MAX_SEARCH_FILTER_DOMAINS each, and cannot both be set.",
            )
        }
        val outputTokens = maxOutputTokens.coerceIn(MIN_MAX_OUTPUT_TOKENS, MAX_MAX_OUTPUT_TOKENS)
        val response = send(searchRequest(token, trimmedQuery, trimmedModel, allowed, excluded, outputTokens))
        val status = response.statusCode()
        val body = response.body()
        if (status == 401 || status == 403) {
            throw SuperGrokQuotaException("Grok auth expired. Log in to SuperGrok again from settings.", status, body)
        }
        if (status !in 200..299) {
            throw SuperGrokQuotaException("Grok web search failed (HTTP $status). Try again later.", status, body)
        }
        return parseSearchResponse(body)
    }

    private fun searchRequest(
        accessToken: String,
        query: String,
        model: String,
        allowedDomains: List<String>,
        excludedDomains: List<String>,
        maxOutputTokens: Int,
    ): HttpRequest {
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("input") {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", query)
                })
            }
            putJsonArray("tools") {
                add(buildJsonObject {
                    put("type", "web_search")
                    if (allowedDomains.isNotEmpty() || excludedDomains.isNotEmpty()) {
                        put("filters", buildJsonObject {
                            if (allowedDomains.isNotEmpty()) {
                                putJsonArray("allowed_domains") { allowedDomains.forEach(::add) }
                            }
                            if (excludedDomains.isNotEmpty()) {
                                putJsonArray("excluded_domains") { excludedDomains.forEach(::add) }
                            }
                        })
                    }
                })
            }
            put("max_output_tokens", maxOutputTokens)
        }.toString()

        return HttpRequest.newBuilder()
            .uri(baseUri.resolve(RESPONSES_PATH))
            .timeout(Duration.ofSeconds(90))
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", "openai-usage-quota-intellij")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
    }

    private fun send(request: HttpRequest): HttpResponse<String> {
        return try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: IOException) {
            throw SuperGrokQuotaException("Grok web search failed. Check your connection.", 0, null, exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SuperGrokQuotaException("Grok web search failed. Check your connection.", 0, null, exception)
        }
    }

    private fun parseSearchResponse(body: String): String {
        val root = try {
            JsonSupport.json.parseToJsonElement(body) as? JsonObject
        } catch (exception: Exception) {
            throw SuperGrokQuotaException("Could not parse Grok web search response.", 200, body, exception)
        } ?: throw SuperGrokQuotaException("Could not parse Grok web search response.", 200, body)

        val answer = outputText(root)
        val webSearchCalls = webSearchCalls(root)
        val citations = citations(root)
        val results = searchResults(webSearchCalls, citations)
        return buildJsonObject {
            root.string("id")?.let { put("id", it) }
            root.string("model")?.let { put("model", it) }
            answer?.let { put("answer", it) }
            putJsonArray("search_results") {
                results.forEach { result ->
                    add(buildJsonObject {
                        put("title", result.title)
                        put("url", result.url)
                        put("snippet", result.snippet)
                    })
                }
            }
            putJsonArray("citations") {
                citations.forEach { citation ->
                    add(buildJsonObject {
                        put("url", citation.url)
                        citation.title?.let { put("title", it) }
                        citation.startIndex?.let { put("start_index", it) }
                        citation.endIndex?.let { put("end_index", it) }
                    })
                }
            }
            putJsonArray("web_search_calls") {
                webSearchCalls.forEach { call ->
                    add(buildJsonObject {
                        call.query?.let { put("query", it) }
                        putJsonArray("sources") {
                            call.sources.forEach { source ->
                                add(buildJsonObject {
                                    put("url", source.url)
                                    source.title?.let { put("title", it) }
                                })
                            }
                        }
                    })
                }
            }
        }.toString()
    }

    private fun outputText(root: JsonObject): String? {
        return root.array("output")
            ?.mapNotNull { item ->
                (item as? JsonObject)
                    ?.array("content")
                    ?.mapNotNull { content -> (content as? JsonObject)?.takeIf { it.string("type") == "output_text" }?.string("text") }
                    ?.joinToString("")
            }
            ?.joinToString("\n")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun webSearchCalls(root: JsonObject): List<WebSearchCall> {
        val calls = mutableListOf<WebSearchCall>()
        root.array("output")?.forEach { item ->
            val obj = item as? JsonObject ?: return@forEach
            if (obj.string("type") != "web_search_call") return@forEach
            val action = obj.obj("action")
            val sources = action?.array("sources")?.mapNotNull { source ->
                (source as? JsonObject)?.toSource()
            }.orEmpty()
            calls += WebSearchCall(query = action?.string("query"), sources = sources)
        }
        return calls
    }

    private fun citations(root: JsonObject): List<Citation> {
        val citations = mutableListOf<Citation>()
        root.array("output")?.forEach { item ->
            (item as? JsonObject)?.array("content")?.forEach { content ->
                val annotations = (content as? JsonObject)?.array("annotations") ?: return@forEach
                annotations.forEach { annotation ->
                    (annotation as? JsonObject)?.toCitation()?.let(citations::add)
                }
            }
        }
        return citations.distinctBy { it.url }
    }

    private fun searchResults(calls: List<WebSearchCall>, citations: List<Citation>): List<SearchResult> {
        val citationTitles = citations.associate { it.url to it.title }
        return calls.flatMap { it.sources }
            .distinctBy { it.url }
            .map { source ->
                val title = source.title ?: citationTitles[source.url] ?: source.url
                SearchResult(title = title, url = source.url, snippet = "")
            }
    }

    private fun JsonObject.toSource(): Source? {
        val url = string("url")?.takeIf { it.isNotBlank() } ?: return null
        return Source(url = url, title = string("title"))
    }

    private fun JsonObject.toCitation(): Citation? {
        if (string("type") != "url_citation") return null
        val url = string("url")?.takeIf { it.isNotBlank() } ?: return null
        return Citation(
            url = url,
            title = string("title"),
            startIndex = int("start_index"),
            endIndex = int("end_index"),
        )
    }

    private fun parseDomainList(rawDomains: String?): List<String>? {
        val domains = rawDomains
            ?.split(',', '\n')
            ?.map { it.trim().lowercase(Locale.ROOT).trim('.') }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        if (domains.size > MAX_SEARCH_FILTER_DOMAINS) return null
        return domains.takeIf { list -> list.all { DOMAIN_PATTERN.matches(it) } }
    }

    private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

    private fun JsonObject.array(name: String): JsonArray? = this[name] as? JsonArray

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

    private data class WebSearchCall(val query: String?, val sources: List<Source>)

    private data class Source(val url: String, val title: String?)

    private data class Citation(val url: String, val title: String?, val startIndex: Int?, val endIndex: Int?)

    private data class SearchResult(val title: String, val url: String, val snippet: String)

    companion object {
        const val DEFAULT_MODEL = "grok-4.3"
        const val DEFAULT_MAX_OUTPUT_TOKENS = 512
        const val MIN_MAX_OUTPUT_TOKENS = 16
        const val MAX_MAX_OUTPUT_TOKENS = 8192
        private const val RESPONSES_PATH = "responses"
        private const val MAX_SEARCH_FILTER_DOMAINS = 5
        private val DEFAULT_BASE_URI = URI.create("https://api.x.ai/v1/")
        private val DOMAIN_PATTERN = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+")

        fun createDefault(): SuperGrokWebSearchClient = SuperGrokWebSearchClient()

        private fun defaultHttpClient(): HttpClient {
            return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build()
        }
    }
}
