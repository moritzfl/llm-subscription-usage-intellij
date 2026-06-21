package de.moritzf.quota.kimi

import com.sun.net.httpserver.HttpServer
import de.moritzf.quota.shared.JsonSupport
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.util.ArrayDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class KimiWebSearchClientTest {
    @Test
    fun postsSearchRequestWithOAuthHeadersAndClampedLimit() {
        TestKimiServer(
            searchBody = """
                {
                  "search_results": [{
                    "title": "Kimi Code",
                    "url": "https://example.test/kimi",
                    "snippet": "Kimi search result",
                    "content": "Full page content",
                    "date": "2026-06-13"
                  }]
                }
            """.trimIndent(),
        ).use { server ->
            val client = newClient(server)

            val result = client.webSearch(
                KimiCredentials(accessToken = "kimi-token"),
                "  Kimi Code news  ",
                limit = 50,
                includeContent = true,
            )

            val responseBody = parseObject(result.body)
            val item = responseBody["search_results"]!!.jsonArray[0].jsonObject
            assertEquals("Kimi Code", item["title"]!!.jsonPrimitive.content)
            assertEquals("https://example.test/kimi", item["url"]!!.jsonPrimitive.content)
            assertEquals("Kimi search result", item["snippet"]!!.jsonPrimitive.content)
            assertEquals("Full page content", item["content"]!!.jsonPrimitive.content)
            assertEquals("2026-06-13", item["date"]!!.jsonPrimitive.content)
            assertEquals(KimiCredentials(accessToken = "kimi-token"), result.credentials)

            val request = assertNotNull(server.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("POST", request.method)
            assertEquals("/search", request.path)
            assertEquals("Bearer kimi-token", request.firstHeader("Authorization"))
            assertEquals("application/json", request.firstHeader("Accept"))
            assertEquals("KimiCLI/1.40.0", request.firstHeader("User-Agent"))
            assertEquals("kimi_cli", request.firstHeader("X-Msh-Platform"))
            assertEquals("1.40.0", request.firstHeader("X-Msh-Version"))

            val body = parseObject(request.body)
            assertEquals("Kimi Code news", body["text_query"]!!.jsonPrimitive.content)
            assertEquals(20, body["limit"]!!.jsonPrimitive.int)
            assertTrue(body["enable_page_crawling"]!!.jsonPrimitive.boolean)
            assertEquals(30, body["timeout_seconds"]!!.jsonPrimitive.int)
        }
    }

    @Test
    fun refreshesExpiredCredentialsBeforeSearching() {
        TestKimiServer(
            tokenBody = """
                {
                  "access_token": "fresh-token",
                  "refresh_token": "refresh-2",
                  "expires_in": 3600,
                  "scope": "offline",
                  "token_type": "Bearer"
                }
            """.trimIndent(),
        ).use { server ->
            val client = newClient(server)

            val result = client.webSearch(
                KimiCredentials(
                    accessToken = "stale-token",
                    refreshToken = "refresh-1",
                    expiresAtEpochSeconds = 0.0,
                ),
                "Kimi Code news",
            )

            assertEquals("fresh-token", result.credentials.accessToken)
            assertEquals("refresh-2", result.credentials.refreshToken)
            assertEquals("offline", result.credentials.scope)
            assertTrue(assertNotNull(result.credentials.expiresAtEpochSeconds) > 0)

            val tokenRequest = assertNotNull(server.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("/token", tokenRequest.path)
            assertTrue(tokenRequest.body.contains("grant_type=refresh_token"))
            assertTrue(tokenRequest.body.contains("refresh_token=refresh-1"))

            val searchRequest = assertNotNull(server.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("/search", searchRequest.path)
            assertEquals("Bearer fresh-token", searchRequest.firstHeader("Authorization"))
        }
    }

    @Test
    fun refreshesCredentialsAndRetriesAfterUnauthorizedSearch() {
        TestKimiServer(
            searchResponses = listOf(
                401 to "{\"error\":\"expired\"}",
                200 to "{\"search_results\":[]}",
            ),
            tokenBody = """
                {
                  "access_token": "fresh-token",
                  "refresh_token": "refresh-2",
                  "expires_in": 3600
                }
            """.trimIndent(),
        ).use { server ->
            val client = newClient(server)

            val result = client.webSearch(
                KimiCredentials(
                    accessToken = "stale-token",
                    refreshToken = "refresh-1",
                    expiresAtEpochSeconds = 9_999_999_999.0,
                ),
                "Kimi Code news",
            )

            assertEquals("fresh-token", result.credentials.accessToken)
            assertEquals("refresh-2", result.credentials.refreshToken)

            val firstSearch = assertNotNull(server.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("/search", firstSearch.path)
            assertEquals("Bearer stale-token", firstSearch.firstHeader("Authorization"))
            val tokenRequest = assertNotNull(server.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("/token", tokenRequest.path)
            assertTrue(tokenRequest.body.contains("refresh_token=refresh-1"))
            val retrySearch = assertNotNull(server.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("/search", retrySearch.path)
            assertEquals("Bearer fresh-token", retrySearch.firstHeader("Authorization"))
        }
    }

    @Test
    fun returnsProviderJsonWithoutFilteringResults() {
        TestKimiServer(
            searchBody = """
                {
                  "search_results": [
                    {
                      "title": "First",
                      "url": "https://example.test/first",
                      "snippet": "First result",
                      "content": "First page content"
                    },
                    {
                      "title": "Second",
                      "url": "https://example.test/second",
                      "snippet": "Second result",
                      "content": "Second page content"
                    }
                  ]
                }
            """.trimIndent(),
        ).use { server ->
            val client = newClient(server)

            val result = client.webSearch(
                KimiCredentials(accessToken = "kimi-token"),
                "Kimi Code news",
                limit = 1,
                includeContent = false,
            )

            val results = parseObject(result.body)["search_results"]!!.jsonArray
            assertEquals(2, results.size)
            assertEquals("First page content", results[0].jsonObject["content"]!!.jsonPrimitive.content)
            assertEquals("Second page content", results[1].jsonObject["content"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun rejectsBlankQueriesBeforeCallingUpstream() {
        TestKimiServer().use { server ->
            val client = newClient(server)

            val exception = assertFailsWith<KimiQuotaException> {
                client.webSearch(KimiCredentials(accessToken = "kimi-token"), "   ")
            }

            assertEquals("Search query is required.", exception.message)
            assertNull(server.requests.poll(500, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun reportsUpstreamErrors() {
        TestKimiServer(searchStatus = 503, searchBody = "{\"error\":\"temporarily unavailable\"}").use { server ->
            val client = newClient(server)

            val exception = assertFailsWith<KimiQuotaException> {
                client.webSearch(KimiCredentials(accessToken = "kimi-token"), "Kimi Code news")
            }

            assertEquals("Kimi web search failed (HTTP 503). Try again later.", exception.message)
            assertEquals(503, exception.statusCode)
            assertEquals("{\"error\":\"temporarily unavailable\"}", exception.rawBody)
        }
    }

    private fun newClient(server: TestKimiServer): KimiWebSearchClient {
        return KimiWebSearchClient(
            httpClient = httpClient,
            searchEndpoint = server.baseUri.resolve("/search"),
            tokenEndpoint = server.baseUri.resolve("/token"),
        )
    }

    private class TestKimiServer(
        private val searchBody: String = "{\"search_results\":[]}",
        private val searchStatus: Int = 200,
        searchResponses: List<Pair<Int, String>>? = null,
        private val tokenBody: String = "{\"access_token\":\"fresh-token\",\"expires_in\":3600}",
        private val tokenStatus: Int = 200,
    ) : AutoCloseable {
        val requests = LinkedBlockingQueue<CapturedRequest>()
        private val queuedSearchResponses = ArrayDeque(searchResponses ?: listOf(searchStatus to searchBody))
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val baseUri: URI

        init {
            server.createContext("/") { exchange ->
                val requestBody = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                requests += CapturedRequest(
                    method = exchange.requestMethod,
                    path = exchange.requestURI.rawPath,
                    headers = exchange.requestHeaders.mapValues { it.value.toList() },
                    body = requestBody,
                )

                val (status, body) = when (exchange.requestURI.rawPath) {
                    "/search" -> nextSearchResponse()
                    "/token" -> tokenStatus to tokenBody
                    else -> 404 to "{}"
                }
                val response = body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, response.size.toLong())
                exchange.responseBody.use { output -> output.write(response) }
            }
            server.start()
            baseUri = URI.create("http://127.0.0.1:${server.address.port}")
        }

        private fun nextSearchResponse(): Pair<Int, String> {
            if (queuedSearchResponses.size > 1) {
                return queuedSearchResponses.removeFirst()
            }
            return queuedSearchResponses.firstOrNull() ?: (searchStatus to searchBody)
        }

        override fun close() {
            server.stop(0)
        }
    }

    private data class CapturedRequest(
        val method: String,
        val path: String,
        val headers: Map<String, List<String>>,
        val body: String,
    ) {
        fun firstHeader(name: String): String? {
            return headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
        }
    }

    private fun parseObject(value: String) = JsonSupport.json.parseToJsonElement(value).jsonObject

    private companion object {
        val httpClient: HttpClient = HttpClient.newHttpClient()
    }
}
