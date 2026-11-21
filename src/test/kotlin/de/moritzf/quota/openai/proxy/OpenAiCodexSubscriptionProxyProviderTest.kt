package de.moritzf.quota.openai.proxy

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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OpenAiCodexSubscriptionProxyProviderTest {
    @Test
    fun advertisesCodexModelsWithOaPrefix() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = get(proxy.port, "/v1/models")

                assertEquals(200, response.statusCode())
                val models =
                    JsonHelper.JSON.parseToJsonElement(response.body())
                        .jsonObject["data"]!!
                        .jsonArray
                        .associate { it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject }
                val ids = models.keys
                assertEquals(listOf("oa-gpt-6.2-sol", "oa-gpt-6.2-astra"), ids.take(2))
                assertTrue("oa-gpt-6.1-sol" in ids)
                assertTrue("oa-gpt-6-astra" in ids)
                assertTrue("oa-gpt-6-sol" in ids)
                assertTrue("oa-gpt-6-luna" in ids)
                assertTrue("oa-gpt-5.6-sol" in ids)
                assertTrue("oa-gpt-6.1-terra" in ids)
                assertTrue("oa-gpt-5.6-terra" in ids)
                assertTrue("oa-gpt-5.6-luna" in ids)
                assertTrue("oa-gpt-reserve" in ids)
                assertTrue("oa-gpt-5.5" in ids)
                assertFalse(ids.any { "(" in it })
                assertTrue(ids.all { it.startsWith(OpenAiCodexSubscriptionProxyProvider.PREFIX) })

                val infoResponse = get(proxy.port, "/v1/model/info")
                assertEquals(200, infoResponse.statusCode())
                val info =
                    JsonHelper.JSON.parseToJsonElement(infoResponse.body())
                        .jsonObject["data"]!!
                        .jsonArray
                        .associate {
                            it.jsonObject["id"]!!.jsonPrimitive.content to
                                it.jsonObject["model_info"]!!.jsonObject
                        }
                for ((id, maxInput) in
                    listOf("oa-gpt-6.2-sol" to "500000", "oa-gpt-6.2-astra" to "272000")) {
                    val reasoning = models.getValue(id)["reasoning"]!!.jsonObject
                    val efforts = listOf("low", "medium", "high", "xhigh", "max", "ultra")
                    val modelInfo = info.getValue(id)
                    assertEquals("true", reasoning["mandatory"]!!.jsonPrimitive.content)
                    assertEquals(
                        efforts.reversed(),
                        reasoning["supported_efforts"]!!.jsonArray.map { it.jsonPrimitive.content },
                    )
                    assertEquals(maxInput, modelInfo["max_input_tokens"]!!.jsonPrimitive.content)
                    assertEquals("128000", modelInfo["max_output_tokens"]!!.jsonPrimitive.content)
                    assertEquals(
                        efforts,
                        modelInfo["reasoning_effort_levels"]!!.jsonArray.map {
                            it.jsonPrimitive.content
                        },
                    )
                }
                assertNull(upstream.requests.poll())
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
                val response =
                    post(
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
                assertNull(request.firstHeader("version"))
                assertEquals("openai-usage-quota-plugin", request.firstHeader("originator"))
                assertTrue(
                    request.firstHeader("User-Agent")!!.startsWith("openai-usage-quota-plugin/"),
                )
                val upstreamBody = JsonHelper.JSON.parseToJsonElement(request.body).jsonObject
                assertEquals("gpt-5.5", upstreamBody["model"]!!.jsonPrimitive.content)
                assertEquals("true", upstreamBody["stream"]!!.jsonPrimitive.content)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun fitsGpt62EffortForChatAndResponsesAndDefaultsToSol() {
        TestUpstream(responseBody = COMPLETED_RESPONSE_STREAM_WITH_TEXT).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val chat =
                    post(
                        proxy.port,
                        "/v1/chat/completions",
                        """{"messages":[{"role":"user","content":"Say pong"}],"reasoning_effort":"none"}""",
                    )
                assertEquals(200, chat.statusCode(), chat.body())
                val responses =
                    post(
                        proxy.port,
                        "/v1/responses",
                        """{"model":"oa-gpt-6.2-astra","input":"Say pong","reasoning":{"effort":"ultra"}}""",
                    )
                assertEquals(200, responses.statusCode(), responses.body())
                for ((model, effort) in
                    listOf("gpt-6.2-sol" to "low", "gpt-6.2-astra" to "ultra")) {
                    val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                    val body = JsonHelper.JSON.parseToJsonElement(request.body).jsonObject
                    assertEquals(model, body["model"]!!.jsonPrimitive.content)
                    assertEquals(
                        effort,
                        body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content,
                    )
                }
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
                val response =
                    post(
                        proxy.port,
                        "/v1/responses",
                        "{\"model\":\"oa-gpt-unlisted\",\"input\":\"Say pong\"}",
                    )

                assertEquals(200, response.statusCode())
                val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/backend-api/codex/responses", request.path)
                val upstreamBody = JsonHelper.JSON.parseToJsonElement(request.body).jsonObject
                assertEquals("gpt-unlisted", upstreamBody["model"]!!.jsonPrimitive.content)
                val input = upstreamBody["input"]!!.jsonArray
                assertEquals("message", input[0].jsonObject["type"]!!.jsonPrimitive.content)
                assertEquals("user", input[0].jsonObject["role"]!!.jsonPrimitive.content)
                assertEquals(
                    "Say pong",
                    input[0]
                        .jsonObject["content"]!!
                        .jsonArray[0]
                        .jsonObject["text"]!!
                        .jsonPrimitive
                        .content,
                )
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun rejectsChatGptRetiredModelsWithoutUpstreamRequest() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                for (model in
                    listOf(
                        "oa-gpt-5.4",
                        "oa-gpt-5.4-mini",
                        "oa-gpt-5.2",
                        "oa-gpt-5.3-codex",
                        "oa-gpt-5.5-pro",
                    )) {
                    val response =
                        post(
                            proxy.port,
                            "/v1/chat/completions",
                            """{"model":"$model","messages":[]}""",
                        )
                    assertEquals(400, response.statusCode(), model)
                    assertTrue(
                        response
                            .body()
                            .contains("not supported when using Codex with a ChatGPT account"),
                        response.body(),
                    )
                }
                assertNull(upstream.requests.poll(200, TimeUnit.MILLISECONDS))
            } finally {
                proxy.stop()
            }
        }
    }

    private fun newProxy(upstreamBaseUri: URI): TestProxy {
        val port = freePort()
        val provider =
            OpenAiCodexSubscriptionProxyProvider(
                accessTokenProvider = { "codex-token" },
                accountIdProvider = { "account-1" },
                upstreamBaseUri = upstreamBaseUri,
                requestLogDir =
                    Files.createTempDirectory("codex-subscription-proxy-test-logs").toString(),
            )
        return TestProxy(
            port = port,
            server =
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

    private data class TestProxy(val port: Int, val server: SubscriptionProxyServer) {
        fun start() = server.start()

        fun stop() = server.stop(gracePeriodMillis = 0)
    }

    private class TestUpstream(
        private val responseBody: String = "{\"ok\":true}",
        private val responseContentType: String = "text/event-stream",
    ) : AutoCloseable {
        val requests = LinkedBlockingQueue<CapturedRequest>()
        private val server =
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val baseUri: URI

        init {
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                requests +=
                    CapturedRequest(
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
        private const val COMPLETED_RESPONSE_STREAM_WITH_TEXT =
            "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"pong\"}]}],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n"
    }
}
