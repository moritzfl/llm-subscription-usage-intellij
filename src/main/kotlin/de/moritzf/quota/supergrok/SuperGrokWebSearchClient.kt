package de.moritzf.quota.supergrok

import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.McpJson
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

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
        return McpJson.providerJsonOrRaw(body)
    }

    private fun searchRequest(
        accessToken: String,
        query: String,
        model: String,
        allowedDomains: List<String>,
        excludedDomains: List<String>,
        maxOutputTokens: Int,
    ): HttpRequest {
        val filters = searchFilters(allowedDomains, excludedDomains)
        val body = JsonSupport.json.encodeToString(
            GrokResponsesRequestDto(
                model = model,
                input = listOf(GrokResponsesInputDto(role = "user", content = query)),
                tools = listOf(GrokResponsesToolDto(type = "web_search", filters = filters)),
                maxOutputTokens = maxOutputTokens,
            ),
        )

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

    private fun parseDomainList(rawDomains: String?): List<String>? {
        val domains = rawDomains
            ?.split(',', '\n')
            ?.map { it.trim().lowercase(Locale.ROOT).trim('.') }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        if (domains.size > MAX_SEARCH_FILTER_DOMAINS) return null
        return domains.takeIf { list -> list.all { DOMAIN_PATTERN.matches(it) } }
    }

    private fun searchFilters(
        allowedDomains: List<String>,
        excludedDomains: List<String>,
    ): GrokSearchFiltersDto? {
        return if (allowedDomains.isEmpty() && excludedDomains.isEmpty()) {
            null
        } else {
            GrokSearchFiltersDto(
                allowedDomains = allowedDomains.takeIf { it.isNotEmpty() },
                excludedDomains = excludedDomains.takeIf { it.isNotEmpty() },
            )
        }
    }

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

@Serializable
private data class GrokResponsesRequestDto(
    val model: String,
    val input: List<GrokResponsesInputDto>,
    val tools: List<GrokResponsesToolDto>,
    @SerialName("max_output_tokens") val maxOutputTokens: Int,
)

@Serializable
private data class GrokResponsesInputDto(
    val role: String,
    val content: String,
)

@Serializable
private data class GrokResponsesToolDto(
    val type: String,
    val filters: GrokSearchFiltersDto? = null,
)

@Serializable
private data class GrokSearchFiltersDto(
    @SerialName("allowed_domains") val allowedDomains: List<String>? = null,
    @SerialName("excluded_domains") val excludedDomains: List<String>? = null,
)
