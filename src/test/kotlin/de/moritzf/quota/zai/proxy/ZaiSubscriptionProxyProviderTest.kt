package de.moritzf.quota.zai.proxy

import com.sun.net.httpserver.HttpServer
import de.moritzf.proxy.server.JsonHelper
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

class ZaiSubscriptionProxyProviderTest {
    @Test
    fun advertisesZaiModelsWithZaPrefixAndRewritesUpstreamModel() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.server.start()

                val modelsResponse = get(proxy.port, "/v1/models")
                assertEquals(200, modelsResponse.statusCode())
                val ids =
                    JsonHelper.JSON.parseToJsonElement(modelsResponse.body())
                        .jsonObject["data"]!!
                        .jsonArray
                        .map { it.jsonObject["id"]!!.jsonPrimitive.content }
                assertTrue("za-glm-5.3" in ids && "za-glm-5.3-flash" in ids, ids.toString())
                assertTrue("za-glm-5.2" !in ids, "the plan routes older GLM models to GLM-5.3")
                assertTrue(ids.all { it.startsWith(ZaiSubscriptionProxyProvider.PREFIX) })

                val chatResponse =
                    post(
                        proxy.port,
                        "/v1/chat/completions",
                        "{\"model\":\"za-glm-5.3\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                    )

                assertEquals(200, chatResponse.statusCode())
                val chatRequest =
                    assertNotNull(
                        generateSequence { upstream.requests.poll(2, TimeUnit.SECONDS) }
                            .firstOrNull { it.path.endsWith("/chat/completions") }
                    )
                assertEquals("/api/coding/paas/v4/chat/completions", chatRequest.path)
                assertEquals("Bearer zai-key", chatRequest.firstHeader("Authorization"))
                assertTrue(chatRequest.body.contains("\"model\":\"glm-5.3\""), chatRequest.body)
            } finally {
                proxy.server.stop(gracePeriodMillis = 0)
            }
        }
    }

    @Test
    fun forwardsPrefixedModelsMissingFromStaticList() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.server.start()

                val chatResponse =
                    post(
                        proxy.port,
                        "/v1/chat/completions",
                        "{\"model\":\"za-glm-5.4\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                    )

                assertEquals(200, chatResponse.statusCode())
                val chatRequest =
                    assertNotNull(
                        generateSequence { upstream.requests.poll(2, TimeUnit.SECONDS) }
                            .firstOrNull { it.path.endsWith("/chat/completions") }
                    )
                assertEquals("/api/coding/paas/v4/chat/completions", chatRequest.path)
                assertTrue(chatRequest.body.contains("\"model\":\"glm-5.4\""), chatRequest.body)
                assertTrue(!chatRequest.body.contains("za-glm-5.4"), chatRequest.body)
            } finally {
                proxy.server.stop(gracePeriodMillis = 0)
            }
        }
    }

    @Test
    fun usesThePlanEndpointByDefault() {
        assertEquals(
            URI.create("https://api.z.ai/api/coding/paas/v4"),
            ZaiSubscriptionProxyProvider.DEFAULT_UPSTREAM_BASE_URI,
        )
    }

    @Test
    fun advertisesTheModelsThePlanEndpointLists() {
        TestUpstream(
                modelsBody =
                    """{"object":"list","data":[{"id":"glm-5.3"},{"id":"glm-5.4-preview"},{"id":"embedding-3"},{"id":"glm-5.3"}]}"""
            )
            .use { upstream ->
                val proxy = newProxy(upstream.baseUri)
                try {
                    proxy.server.start()
                    val models =
                        JsonHelper.JSON.parseToJsonElement(get(proxy.port, "/v1/models").body())
                            .jsonObject["data"]!!
                            .jsonArray
                            .map { it.jsonObject["id"]!!.jsonPrimitive.content }
                    assertEquals(
                        listOf("za-glm-5.3", "za-glm-5.4-preview"),
                        models.filter { it.matches(Regex("za-glm-\\d.*")) },
                    )
                    assertTrue("za-glm-5.3-flash" !in models && "za-embedding-3" !in models)
                    val modelsRequest = generateSequence {
                        upstream.requests.poll()
                    }
                        .first { it.path.endsWith("/models") }
                    assertEquals("/api/coding/paas/v4/models", modelsRequest.path)
                    assertEquals("Bearer zai-key", modelsRequest.firstHeader("Authorization"))
                } finally {
                    proxy.server.stop(gracePeriodMillis = 0)
                }
            }
    }

    @Test
    fun glm53ThinksAtLowEffortWhenClientsTurnThinkingOff() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.server.start()
                post(
                    proxy.port,
                    "/v1/chat/completions",
                    """{"model":"za-glm-5.3","thinking":{"type":"disabled"},"messages":[{"role":"user","content":"hi"}]}""",
                )
                val chat = generateSequence {
                    upstream.requests.poll(2, TimeUnit.SECONDS)
                }
                    .first { it.path.endsWith("/chat/completions") }
                val body = JsonHelper.JSON.parseToJsonElement(chat.body).jsonObject
                assertEquals(
                    "enabled",
                    body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content,
                )
                assertEquals("low", body["reasoning_effort"]!!.jsonPrimitive.content)
            } finally {
                proxy.server.stop(gracePeriodMillis = 0)
            }
        }
    }

    @Test
    fun alwaysThinkingLeavesOtherRequestsAlone() {
        fun adapt(json: String) =
            ZaiSubscriptionProxyProvider.alwaysThinking(
                    JsonHelper.JSON.parseToJsonElement(json).jsonObject
                )
                .toString()

        assertEquals(
            """{"model":"glm-5.3-flash","thinking":{"type":"enabled"},"reasoning_effort":"high"}""",
            adapt(
                """{"model":"glm-5.3-flash","thinking":{"type":"disabled"},"reasoning_effort":"high"}"""
            ),
        )
        for (json in
            listOf(
                """{"model":"glm-5.3","thinking":{"type":"enabled"}}""",
                """{"model":"glm-5.3"}""",
                """{"model":"glm-4.7","thinking":{"type":"disabled"}}""",
            )) {
            assertEquals(json, adapt(json))
        }
    }

    private fun newProxy(upstreamBaseUri: URI): TestProxy {
        val port = freePort()
        val provider =
            ZaiSubscriptionProxyProvider(
                apiKeyProvider = { "zai-key" },
                upstreamBaseUri = upstreamBaseUri,
                requestLogDir =
                    Files.createTempDirectory("zai-subscription-proxy-test-logs").toString(),
            )
        return TestProxy(
            port,
            SubscriptionProxyServer(
                port = port,
                localApiKeyProvider = { "local-key" },
                providers = { listOf(provider) },
                requestLogDir =
                    Files.createTempDirectory("subscription-proxy-test-logs").toString(),
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

    private data class TestProxy(val port: Int, val server: SubscriptionProxyServer)

    private class TestUpstream(private val modelsBody: String? = null) : AutoCloseable {
        val requests = LinkedBlockingQueue<CapturedRequest>()
        private val server =
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val baseUri: URI

        init {
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                requests +=
                    CapturedRequest(
                        path = exchange.requestURI.rawPath,
                        headers = exchange.requestHeaders.mapValues { it.value.toList() },
                        body = body,
                    )
                val responseBody =
                    if (exchange.requestURI.rawPath.endsWith("/models") && modelsBody != null) {
                        modelsBody
                    } else {
                        "{\"id\":\"chatcmpl_1\",\"choices\":[]}"
                    }
                val response = responseBody.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { output -> output.write(response) }
            }
            server.start()
            baseUri = URI.create("http://127.0.0.1:${server.address.port}/api/coding/paas/v4")
        }

        override fun close() {
            server.stop(0)
        }
    }

    private data class CapturedRequest(
        val path: String,
        val headers: Map<String, List<String>>,
        val body: String,
    ) {
        fun firstHeader(name: String): String? {
            return headers.entries
                .firstOrNull { it.key.equals(name, ignoreCase = true) }
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
    }
}
