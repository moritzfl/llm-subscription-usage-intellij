package de.moritzf.quota.openai.proxy

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OpenAiProxyServerTest {
    @Test
    fun proxiesResponsesRequestWithCodexAuthentication() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/responses?trace=1"))
                        .header("Authorization", "Bearer local-key")
                        .header("X-API-Key", "must-not-forward")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-5\"}"))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                assertEquals("{\"ok\":true}", response.body())
                val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("POST", request.method)
                assertEquals("/backend-api/codex/responses", request.path)
                assertEquals("trace=1", request.query)
                assertEquals("{\"model\":\"gpt-5\"}", request.body)
                assertEquals("Bearer codex-token", request.firstHeader("Authorization"))
                assertEquals("account-1", request.firstHeader("ChatGPT-Account-Id"))
                assertEquals("responses=experimental", request.firstHeader("OpenAI-Beta"))
                assertEquals("openai_usage_quota_intellij", request.firstHeader("originator"))
                assertNull(request.firstHeader("X-API-Key"))
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun rejectsRequestsWithoutLocalApiKey() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/responses"))
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(401, response.statusCode())
                assertNull(upstream.requests.poll(200, TimeUnit.MILLISECONDS))
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun returnsServiceUnavailableWhenOpenAiTokenIsMissing() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri, accessToken = null)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/responses"))
                        .header("Authorization", "Bearer local-key")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(503, response.statusCode())
                assertNull(upstream.requests.poll(200, TimeUnit.MILLISECONDS))
            } finally {
                proxy.stop()
            }
        }
    }

    private fun newProxy(upstreamBaseUri: URI, accessToken: String? = "codex-token"): TestProxy {
        val port = freePort()
        return TestProxy(
            port = port,
            server = OpenAiProxyServer(
                port = port,
                localApiKeyProvider = { "local-key" },
                accessTokenProvider = { accessToken },
                accountIdProvider = { "account-1" },
                upstreamBaseUri = upstreamBaseUri,
            ),
        )
    }

    private data class TestProxy(val port: Int, val server: OpenAiProxyServer) {
        fun start() = server.start()
        fun stop() = server.stop()
    }

    private class TestUpstream : AutoCloseable {
        val requests = LinkedBlockingQueue<CapturedRequest>()
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val baseUri: URI

        init {
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                requests += CapturedRequest(
                    method = exchange.requestMethod,
                    path = exchange.requestURI.rawPath,
                    query = exchange.requestURI.rawQuery,
                    headers = exchange.requestHeaders.mapValues { it.value.toList() },
                    body = body,
                )
                val response = "{\"ok\":true}".toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json")
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
        val query: String?,
        val headers: Map<String, List<String>>,
        val body: String,
    ) {
        fun firstHeader(name: String): String? {
            return headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
        }
    }

    private fun freePort(): Int {
        return ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
    }

    private companion object {
        val httpClient: HttpClient = HttpClient.newHttpClient()
    }
}
