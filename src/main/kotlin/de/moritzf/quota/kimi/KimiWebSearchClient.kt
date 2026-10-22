package de.moritzf.quota.kimi

import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.McpJson
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

open class KimiWebSearchClient(
    private val httpClient: HttpClient = defaultHttpClient(),
    /** Overrides the login region's endpoints, for tests. */
    private val searchEndpoint: URI? = null,
    tokenEndpoint: URI? = null,
    private val deviceId: String = KimiDeviceHeaders.processDeviceId,
) {
    private val credentialRefresher = KimiCredentialRefresher(httpClient, tokenEndpoint, deviceId)

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

        val resultLimit = limit.coerceIn(MIN_LIMIT, MAX_LIMIT)
        val (body, usableCredentials) =
            authorized(credentials, "Kimi web search") { token, region ->
                searchRequest(token, region, trimmedQuery, resultLimit, includeContent)
            }
        return KimiWebSearchFetchResult(McpJson.providerJson(body), usableCredentials)
    }

    /**
     * Reads a page with Kimi Code's fetch service, which answers with the page as Markdown text,
     * returned as JSON with the URL.
     */
    open fun webFetch(credentials: KimiCredentials, url: String): KimiWebSearchFetchResult {
        val target = normalizeHttpUrl(url)
        val (markdown, usableCredentials) =
            authorized(credentials, "Kimi web fetch") { token, region ->
                fetchRequest(token, region, target)
            }
        val body = buildJsonObject {
            put("url", target)
            put("format", "markdown")
            put("content", markdown)
        }
        return KimiWebSearchFetchResult(body.toString(), usableCredentials)
    }

    /** Sends with a fresh token, refreshing once after the server rejects it. */
    private fun authorized(
        credentials: KimiCredentials,
        action: String,
        request: (String, KimiRegion) -> HttpRequest,
    ): Pair<String, KimiCredentials> {
        var usableCredentials = credentialRefresher.refreshIfNeeded(credentials)
        fun token() =
            usableCredentials.accessToken.ifBlank {
                throw KimiQuotaException("Kimi login required. Log in from settings.")
            }
        var response = send(request(token(), usableCredentials.kimiRegion))
        if (response.statusCode().isUnauthorized()) {
            usableCredentials =
                credentialRefresher.refresh(usableCredentials)
                    ?: throw KimiQuotaException(
                        "Session expired. Log in to Kimi again from settings.",
                        response.statusCode(),
                        response.body(),
                    )
            response = send(request(token(), usableCredentials.kimiRegion))
        }
        val status = response.statusCode()
        val body = response.body()
        if (status == 401 || status == 403) {
            throw KimiQuotaException(
                "Session expired. Log in to Kimi again from settings.",
                status,
                body,
            )
        }
        if (status !in 200..299) {
            throw KimiQuotaException(
                "$action failed (HTTP $status). Try again later.",
                status,
                body,
            )
        }
        return body to usableCredentials
    }

    private fun fetchRequest(accessToken: String, region: KimiRegion, url: String): HttpRequest {
        val builder =
            HttpRequest.newBuilder()
                .uri(searchEndpoint?.resolve("fetch") ?: region.api("fetch"))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer $accessToken")
                .header("Content-Type", "application/json")
                .header("Accept", "text/markdown")
                .header("User-Agent", USER_AGENT)
        KimiDeviceHeaders.all(deviceId).forEach { (key, value) -> builder.header(key, value) }
        val body = buildJsonObject { put("url", url) }.toString()
        return builder.POST(HttpRequest.BodyPublishers.ofString(body)).build()
    }

    private fun searchRequest(
        accessToken: String,
        region: KimiRegion,
        query: String,
        limit: Int,
        includeContent: Boolean,
    ): HttpRequest {
        val body =
            JsonSupport.json.encodeToString(
                KimiSearchRequestDto(
                    textQuery = query,
                    limit = limit,
                    enablePageCrawling = includeContent,
                    timeoutSeconds = SEARCH_TIMEOUT_SECONDS,
                )
            )

        val builder =
            HttpRequest.newBuilder()
                .uri(searchEndpoint ?: region.api("search"))
                .timeout(Duration.ofSeconds(180))
                .header("Authorization", "Bearer $accessToken")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
        KimiDeviceHeaders.all(deviceId).forEach { (key, value) -> builder.header(key, value) }
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

        internal fun normalizeHttpUrl(value: String): String {
            val trimmed = value.trim()
            if (trimmed.isBlank()) throw KimiQuotaException("URL is required.")
            val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
            val uri = runCatching { URI(withScheme) }.getOrNull()
            val scheme = uri?.scheme?.lowercase()
            if (uri?.host.isNullOrBlank() || (scheme != "http" && scheme != "https")) {
                throw KimiQuotaException("URL must be http or https.")
            }
            return withScheme
        }

        fun createDefault(
            deviceId: String = KimiDeviceHeaders.processDeviceId
        ): KimiWebSearchClient = KimiWebSearchClient(deviceId = deviceId)

        private fun defaultHttpClient(): HttpClient {
            return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
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
