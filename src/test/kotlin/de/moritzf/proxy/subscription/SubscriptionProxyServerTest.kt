package de.moritzf.proxy.subscription

import com.sun.net.httpserver.HttpServer
import de.moritzf.proxy.logging.RequestLogger
import de.moritzf.proxy.server.JsonHelper
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SubscriptionProxyServerTest {
    @Test
    fun advertisesOnlyConfiguredProviderModels() {
        TestUpstream().use { upstream ->
            val server = newServer(
                providers = listOf(
                    fakeProvider("xai", "SuperGrok", upstream.baseUri, "grok-token", "grok-4.3", "grok-4.3"),
                    fakeProvider("github", "GitHub Copilot", upstream.baseUri, null, "gh-gpt-5.5", "gpt-5.5"),
                ),
            )
            try {
                server.start()
                val response = get(server.port, "/v1/models")

                assertEquals(200, response.statusCode())
                val ids = parseObject(response.body())["data"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
                assertEquals(listOf("grok-4.3"), ids)
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun rejectsUnknownModelsWithoutCallingUpstream() {
        TestUpstream().use { upstream ->
            val server = newServer(
                providers = listOf(fakeProvider("xai", "SuperGrok", upstream.baseUri, "grok-token", "grok-4.3", "grok-4.3")),
            )
            try {
                server.start()
                val response = post(server.port, "/v1/chat/completions", "{\"model\":\"gh-gpt-5.5\",\"messages\":[]}")

                assertEquals(400, response.statusCode())
                assertTrue(response.body().contains("Unknown proxy model"))
                assertNull(upstream.requests.poll(500, TimeUnit.MILLISECONDS))
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun routesByAdvertisedModelAndRewritesUpstreamModel() {
        TestUpstream(responseBody = "{\"id\":\"chatcmpl_1\",\"choices\":[]}").use { grokUpstream ->
            TestUpstream(responseBody = "{\"id\":\"chatcmpl_2\",\"choices\":[]}").use { githubUpstream ->
                val server = newServer(
                    providers = listOf(
                        fakeProvider("xai", "SuperGrok", grokUpstream.baseUri, "grok-token", "grok-4.3", "grok-4.3"),
                        fakeProvider("github", "GitHub Copilot", githubUpstream.baseUri, "gh-token", "gh-gpt-5.5", "gpt-5.5"),
                    ),
                )
                try {
                    server.start()
                    val response = post(
                        server.port,
                        "/v1/chat/completions",
                        "{\"model\":\"gh-gpt-5.5\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                    )

                    assertEquals(200, response.statusCode())
                    assertNull(grokUpstream.requests.poll(500, TimeUnit.MILLISECONDS))
                    val githubRequest = assertNotNull(githubUpstream.requests.poll(2, TimeUnit.SECONDS))
                    assertEquals("/v1/chat/completions", githubRequest.path)
                    assertEquals("Bearer gh-token", githubRequest.firstHeader("Authorization"))
                    assertTrue(githubRequest.body.contains("\"model\":\"gpt-5.5\""), githubRequest.body)
                    assertFalse(githubRequest.body.contains("gh-gpt-5.5"), githubRequest.body)
                } finally {
                    server.stop()
                }
            }
        }
    }

    @Test
    fun returnsLiteLlmModelInfoForAdvertisedModels() {
        TestUpstream().use { upstream ->
            val server = newServer(
                providers = listOf(fakeProvider("github", "GitHub Copilot", upstream.baseUri, "gh-token", "gh-gpt-5.5", "gpt-5.5")),
            )
            try {
                server.start()
                val response = get(server.port, "/v1/model/info")

                assertEquals(200, response.statusCode())
                val modelInfo = parseObject(response.body())["data"]!!.jsonArray[0].jsonObject
                assertEquals("gh-gpt-5.5", modelInfo["model_name"]!!.jsonPrimitive.content)
                assertEquals(
                    "github",
                    modelInfo["model_info"]!!.jsonObject["litellm_provider"]!!.jsonPrimitive.content,
                )
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun rejectsInvalidLocalApiKeyBeforeCallingUpstream() {
        TestUpstream().use { upstream ->
            val server = newServer(
                providers = listOf(fakeProvider("xai", "SuperGrok", upstream.baseUri, "grok-token", "grok-4.3", "grok-4.3")),
            )
            try {
                server.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.port}/v1/models"))
                        .header("Authorization", "Bearer wrong")
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(401, response.statusCode())
                assertNull(upstream.requests.poll(500, TimeUnit.MILLISECONDS))
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun passThroughProviderDropsHttp2PseudoHeaders() {
        assertFalse(PassThroughSubscriptionProxyProvider.shouldForwardResponseHeader(":status"))
        assertFalse(PassThroughSubscriptionProxyProvider.shouldForwardResponseHeader("content-length"))
        assertTrue(PassThroughSubscriptionProxyProvider.shouldForwardResponseHeader("x-request-id"))
    }

    @Test
    fun usesAdvertisedDefaultModelWhenRequestOmitsModel() {
        TestUpstream(responseBody = "{\"id\":\"chatcmpl_1\",\"choices\":[]}").use { upstream ->
            val server = newServer(
                providers = listOf(
                    fakeProvider("xai", "SuperGrok", upstream.baseUri, "grok-token", "grok-4.3", "grok-4.3"),
                    fakeProvider(
                        "github",
                        "GitHub Copilot",
                        upstream.baseUri,
                        "gh-token",
                        "gh-gpt-5.5",
                        "gpt-5.5",
                        isDefault = true,
                    ),
                ),
            )
            try {
                server.start()
                val response = post(server.port, "/v1/chat/completions", "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")

                assertEquals(200, response.statusCode())
                val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertTrue(request.body.contains("\"model\":\"gpt-5.5\""), request.body)
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun fallsBackToAlphabeticallyLatestModelWhenNoDefaultIsDeclared() {
        TestUpstream(responseBody = "{\"id\":\"chatcmpl_1\",\"choices\":[]}").use { upstream ->
            val server = newServer(
                providers = listOf(
                    fakeProvider("openai", "OpenAI", upstream.baseUri, "openai-token", "gpt-5.4", "gpt-5.4"),
                    fakeProvider("openai2", "OpenAI", upstream.baseUri, "openai-token", "gpt-5.5", "gpt-5.5"),
                ),
            )
            try {
                server.start()
                val response = post(server.port, "/v1/chat/completions", "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")

                assertEquals(200, response.statusCode())
                val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertTrue(request.body.contains("\"model\":\"gpt-5.5\""), request.body)
            } finally {
                server.stop()
            }
        }
    }

    private fun fakeProvider(
        id: String,
        name: String,
        upstreamBaseUri: URI,
        token: String?,
        localModel: String,
        upstreamModel: String,
        isDefault: Boolean = false,
    ): SubscriptionProxyProvider {
        return PassThroughSubscriptionProxyProvider(
            id = id,
            displayName = name,
            litellmProvider = id,
            baseUri = upstreamBaseUri,
            accessTokenProvider = { token },
            modelMappingsProvider = { listOf(
                PassThroughSubscriptionProxyProvider.ModelMapping(
                    localId = localModel,
                    upstreamId = upstreamModel,
                    supportedRoutes = setOf(SubscriptionProxyRoute.CHAT_COMPLETIONS, SubscriptionProxyRoute.RESPONSES),
                    isDefault = isDefault,
                ),
            ) },
            requestLogger = RequestLogger(false, Files.createTempDirectory("subscription-proxy-test-logs")),
        )
    }

    private fun newServer(providers: List<SubscriptionProxyProvider>): TestServer {
        val port = freePort()
        return TestServer(
            port = port,
            server = SubscriptionProxyServer(
                port = port,
                localApiKeyProvider = { "local-key" },
                providers = { providers },
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

    private data class TestServer(val port: Int, val server: SubscriptionProxyServer) {
        fun start() = server.start()
        fun stop() = server.stop()
    }

    private class TestUpstream(
        private val responseBody: String = "{\"ok\":true}",
        private val responseContentType: String = "application/json",
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
            baseUri = URI.create("http://127.0.0.1:${server.address.port}/v1")
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

    private fun parseObject(value: String) = JsonHelper.JSON.parseToJsonElement(value).jsonObject

    private fun freePort(): Int {
        ServerSocket(0).use { socket ->
            socket.reuseAddress = true
            return socket.localPort
        }
    }

    companion object {
        private val httpClient: HttpClient = HttpClient.newHttpClient()
    }
}
