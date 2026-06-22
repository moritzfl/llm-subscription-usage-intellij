package de.moritzf.quota.openai.proxy

import com.sun.net.httpserver.HttpServer
import de.moritzf.proxy.subscription.SubscriptionProxyServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import de.moritzf.proxy.server.JsonHelper

class OpenAiCodexSubscriptionProxyProviderTest {
    @Test
    fun advertisesCodexModelsWithOaPrefix() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = get(proxy.port, "/v1/models")

                assertEquals(200, response.statusCode())
                val ids = JsonHelper.JSON.parseToJsonElement(response.body()).jsonObject["data"]!!.jsonArray
                    .map { it.jsonObject["id"]!!.jsonPrimitive.content }
                assertTrue("oa-gpt-5.5" in ids)
                assertTrue(ids.all { it.startsWith(OpenAiCodexSubscriptionProxyProvider.PREFIX) })
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun routesCodexChatCompletionThroughExistingResponsesBridge() {
        TestUpstream(responseBody = COMPLETED_RESPONSE_STREAM_WITH_TEXT).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = post(
                    proxy.port,
                    "/v1/chat/completions",
                    "{\"model\":\"oa-gpt-5.5\",\"messages\":[{\"role\":\"user\",\"content\":\"Say pong\"}]}",
                )

                assertEquals(200, response.statusCode())
                assertTrue(response.body().contains("pong"), response.body())
                val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("POST", request.method)
                assertEquals("/backend-api/codex/responses", request.path)
                assertEquals("Bearer codex-token", request.firstHeader("Authorization"))
                assertEquals("account-1", request.firstHeader("chatgpt-account-id"))
                assertEquals(TEST_CODEX_VERSION, request.firstHeader("version"))
                val upstreamBody = JsonHelper.JSON.parseToJsonElement(request.body).jsonObject
                assertEquals("gpt-5.5", upstreamBody["model"]!!.jsonPrimitive.content)
                assertEquals("true", upstreamBody["stream"]!!.jsonPrimitive.content)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun forwardsPrefixedCodexModelsMissingFromDiscovery() {
        TestUpstream(responseBody = COMPLETED_RESPONSE_STREAM_WITH_TEXT).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = post(
                    proxy.port,
                    "/v1/responses",
                    "{\"model\":\"oa-gpt-5.6\",\"input\":\"Say pong\"}",
                )

                assertEquals(200, response.statusCode())
                val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/backend-api/codex/responses", request.path)
                val upstreamBody = JsonHelper.JSON.parseToJsonElement(request.body).jsonObject
                assertEquals("gpt-5.6", upstreamBody["model"]!!.jsonPrimitive.content)
            } finally {
                proxy.stop()
            }
        }
    }

    private fun newProxy(upstreamBaseUri: URI): TestProxy {
        val port = freePort()
        val provider = OpenAiCodexSubscriptionProxyProvider(
            accessTokenProvider = { "codex-token" },
            accountIdProvider = { "account-1" },
            upstreamBaseUri = upstreamBaseUri,
            requestLogDir = Files.createTempDirectory("codex-subscription-proxy-test-logs").toString(),
            codexVersionProvider = { TEST_CODEX_VERSION },
        )
        return TestProxy(
            port = port,
            server = SubscriptionProxyServer(
                port = port,
                localApiKeyProvider = { "local-key" },
                providers = { listOf(provider) },
                requestLogDir = Files.createTempDirectory("subscription-proxy-test-logs").toString(),
            ),
        )
    }

    private fun get(port: Int, path: String): HttpResponse<String> {
        return httpClient.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .header("Authorization", "Bearer local-key")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    private fun post(port: Int, path: String, body: String): HttpResponse<String> {
        return httpClient.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .header("Authorization", "Bearer local-key")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    private data class TestProxy(val port: Int, val server: SubscriptionProxyServer) {
        fun start() = server.start()
        fun stop() = server.stop()
    }

    private class TestUpstream(
        private val responseBody: String = "{\"ok\":true}",
        private val responseContentType: String = "text/event-stream",
    ) : AutoCloseable {
        val requests = LinkedBlockingQueue<CapturedRequest>()
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
                val response = responseBody.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", responseContentType)
                exchange.sendResponseHeaders(200, response.size.toLong())
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
            return headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value
                ?.firstOrNull()
        }
    }

    private fun freePort(): Int {
        ServerSocket(0).use { socket ->
            socket.reuseAddress = true
            return socket.localPort
        }
    }

    companion object {
        private val httpClient: HttpClient = HttpClient.newHttpClient()
        private const val TEST_CODEX_VERSION = "0.59.0-test"
        private const val COMPLETED_RESPONSE_STREAM_WITH_TEXT =
            "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"pong\"}]}],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n"
    }
}
