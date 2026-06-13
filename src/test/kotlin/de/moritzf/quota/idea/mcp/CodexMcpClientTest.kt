package de.moritzf.quota.idea.mcp

import com.sun.net.httpserver.HttpServer
import de.moritzf.quota.shared.JsonSupport
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CodexMcpClientTest {
    @Test
    fun postsWebSearchToCodexSearchEndpointWithQuotaAuth() {
        TestUpstream(responseBody = "{\"output\":\"search result\"}").use { upstream ->
            val client = newClient(upstream.baseUri)

            val response = client.webSearch("OpenAI news")

            assertFalse(response.isError)
            assertEquals("search result", parseObject(response.body)["output"]!!.jsonPrimitive.content)
            val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("POST", request.method)
            assertEquals("/backend-api/codex/alpha/search", request.path)
            assertEquals("Bearer codex-token", request.firstHeader("Authorization"))
            assertEquals("account-1", request.firstHeader("chatgpt-account-id"))
            assertEquals("responses=experimental", request.firstHeader("OpenAI-Beta"))

            val body = parseObject(request.body)
            assertEquals("gpt-5.5", body["model"]!!.jsonPrimitive.content)
            assertEquals(
                "OpenAI news",
                body["commands"]!!.jsonObject["search_query"]!!.jsonArray[0].jsonObject["q"]!!.jsonPrimitive.content,
            )
            assertEquals(
                "direct",
                body["settings"]!!.jsonObject["allowed_callers"]!!.jsonArray[0].jsonPrimitive.content,
            )
        }
    }

    @Test
    fun postsImageGenerationToCodexImageEndpointWithQuotaAuth() {
        TestUpstream(responseBody = "{\"created\":1,\"data\":[{\"b64_json\":\"cG5n\"}]}").use { upstream ->
            val client = newClient(upstream.baseUri)

            val response = client.imageGeneration("draw a tiny robot")

            assertFalse(response.isError)
            assertEquals("cG5n", parseObject(response.body)["data"]!!.jsonArray[0].jsonObject["b64_json"]!!.jsonPrimitive.content)
            val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("POST", request.method)
            assertEquals("/backend-api/codex/images/generations", request.path)
            assertEquals("Bearer codex-token", request.firstHeader("Authorization"))

            val body = parseObject(request.body)
            assertEquals("draw a tiny robot", body["prompt"]!!.jsonPrimitive.content)
            assertEquals("gpt-image-2", body["model"]!!.jsonPrimitive.content)
            assertEquals(1, body["n"]!!.jsonPrimitive.int)
        }
    }

    @Test
    fun refreshesTokenAndRetriesOnceAfterUpstream401() {
        TestUpstream(responseBody = "{\"output\":\"search result\"}", failFirstRequests = 1, failStatus = 401).use { upstream ->
            val tokens = ArrayDeque(listOf("stale-token", "fresh-token"))
            var refreshedWith: String? = null
            val client = CodexMcpClient(
                accessTokenProvider = { tokens.first() },
                accountIdProvider = { "account-1" },
                tokenRefresher = { stale ->
                    refreshedWith = stale
                    if (tokens.size > 1) tokens.removeFirst()
                    tokens.first()
                },
                httpClient = httpClient,
                upstreamBaseUri = upstream.baseUri,
            )

            val response = client.webSearch("OpenAI news")

            assertFalse(response.isError)
            assertEquals("stale-token", refreshedWith)
            val first = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("Bearer stale-token", first.firstHeader("Authorization"))
            val retry = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("Bearer fresh-token", retry.firstHeader("Authorization"))
        }
    }

    @Test
    fun reportsLoginRequiredWithoutCallingUpstream() {
        TestUpstream().use { upstream ->
            val client = CodexMcpClient(
                accessTokenProvider = { null },
                accountIdProvider = { "account-1" },
                httpClient = httpClient,
                upstreamBaseUri = upstream.baseUri,
            )

            val response = client.webSearch("OpenAI news")

            assertTrue(response.isError)
            assertEquals(
                "OpenAI login required: log in on the OpenAI settings tab, then retry.",
                parseObject(response.body)["error"]!!.jsonPrimitive.content,
            )
            assertNull(upstream.requests.poll(500, TimeUnit.MILLISECONDS))
        }
    }

    private fun newClient(upstreamBaseUri: URI): CodexMcpClient {
        return CodexMcpClient(
            accessTokenProvider = { "codex-token" },
            accountIdProvider = { "account-1" },
            httpClient = httpClient,
            upstreamBaseUri = upstreamBaseUri,
        )
    }

    private class TestUpstream(
        private val responseBody: String = "{\"ok\":true}",
        private val responseStatus: Int = 200,
        private val failFirstRequests: Int = 0,
        private val failStatus: Int = 503,
    ) : AutoCloseable {
        val requests = LinkedBlockingQueue<CapturedRequest>()
        private val requestCount = java.util.concurrent.atomic.AtomicInteger(0)
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val baseUri: URI

        init {
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                requests += CapturedRequest(
                    method = exchange.requestMethod,
                    path = exchange.requestURI.rawPath,
                    headers = exchange.requestHeaders.mapValues { it.value.toList() },
                    body = body,
                )
                val failing = requestCount.incrementAndGet() <= failFirstRequests
                val response = (if (failing) "{\"detail\":\"transient upstream failure\"}" else responseBody)
                    .toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(if (failing) failStatus else responseStatus, response.size.toLong())
                exchange.responseBody.use { output -> output.write(response) }
            }
            server.start()
            baseUri = URI.create("http://127.0.0.1:${server.address.port}/backend-api/codex")
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
