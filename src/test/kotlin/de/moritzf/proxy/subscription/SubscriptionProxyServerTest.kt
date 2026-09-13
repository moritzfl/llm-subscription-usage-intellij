package de.moritzf.proxy.subscription

import com.sun.net.httpserver.HttpServer
import de.moritzf.proxy.fim.CompletionsConfig
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
import kotlinx.serialization.json.boolean
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
                assertEquals("none", modelInfo["model_info"]!!.jsonObject["fim_mode"]!!.jsonPrimitive.content)
                assertEquals(false, modelInfo["model_info"]!!.jsonObject["supports_native_fim"]!!.jsonPrimitive.boolean)
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun usageEndpointCountsChatCompletionTokens() {
        TestUpstream(
            responseBody = "{\"id\":\"chatcmpl_1\",\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":5}}",
        ).use { upstream ->
            val server = newServer(
                providers = listOf(fakeProvider("github", "GitHub Copilot", upstream.baseUri, "gh-token", "gh-gpt-5.5", "gpt-5.5")),
            )
            try {
                server.start()
                val chat = post(
                    server.port,
                    "/v1/chat/completions",
                    "{\"model\":\"gh-gpt-5.5\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                )
                assertEquals(200, chat.statusCode(), chat.body())

                val usage = get(server.port, "/v1/usage")
                assertEquals(200, usage.statusCode())
                val total = parseObject(usage.body())["total"]!!.jsonObject
                assertEquals(3, total["prompt_tokens"]!!.jsonPrimitive.content.toLong())
                assertEquals(5, total["completion_tokens"]!!.jsonPrimitive.content.toLong())
                assertEquals(8, total["total_tokens"]!!.jsonPrimitive.content.toLong())
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun modelInfoIncludesFimAliasAndReportsAdapterOnlyForSelectedModel() {
        TestUpstream().use { upstream ->
            val server = newServer(
                providers = listOf(
                    fakeProvider("github", "GitHub Copilot", upstream.baseUri, "gh-token", "gh-gpt-5.5", "gpt-5.5"),
                    fakeProvider("xai", "SuperGrok", upstream.baseUri, "grok-token", "sg-grok-4.3", "grok-4.3"),
                ),
                completionsConfig = CompletionsConfig(
                    enabled = true,
                    modelLocalId = "gh-gpt-5.5",
                    useChatAdapter = true,
                ),
            )
            try {
                server.start()
                val response = get(server.port, "/v1/model/info")

                assertEquals(200, response.statusCode())
                val data = parseObject(response.body())["data"]!!.jsonArray
                assertEquals("qwen2.5-coder", data[0].jsonObject["model_name"]!!.jsonPrimitive.content)
                assertEquals(
                    "adapter",
                    data[0].jsonObject["model_info"]!!.jsonObject["fim_mode"]!!.jsonPrimitive.content,
                )
                val byName = data.associate { row ->
                    row.jsonObject["model_name"]!!.jsonPrimitive.content to
                        row.jsonObject["model_info"]!!.jsonObject["fim_mode"]!!.jsonPrimitive.content
                }
                assertEquals("adapter", byName["gh-gpt-5.5"])
                assertEquals("none", byName["sg-grok-4.3"])
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun modelInfoReportsNativeFimWhenAdapterIsOff() {
        TestUpstream().use { upstream ->
            val server = newServer(
                providers = listOf(fakeProvider("github", "GitHub Copilot", upstream.baseUri, "gh-token", "gh-gpt-5.5", "gpt-5.5")),
                completionsConfig = CompletionsConfig(
                    enabled = true,
                    modelLocalId = "gh-gpt-5.5",
                    useChatAdapter = false,
                ),
            )
            try {
                server.start()
                val response = get(server.port, "/v1/model/info")

                assertEquals(200, response.statusCode())
                val data = parseObject(response.body())["data"]!!.jsonArray
                assertEquals("qwen2.5-coder", data[0].jsonObject["model_name"]!!.jsonPrimitive.content)
                assertEquals(
                    "native",
                    data[0].jsonObject["model_info"]!!.jsonObject["fim_mode"]!!.jsonPrimitive.content,
                )
                assertEquals(
                    "native",
                    data.first { it.jsonObject["model_name"]!!.jsonPrimitive.content == "gh-gpt-5.5" }
                        .jsonObject["model_info"]!!.jsonObject["fim_mode"]!!.jsonPrimitive.content,
                )
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun modelsEndpointOmitsAnthropicOnlyModelsButModelInfoIncludesThem() {
        TestUpstream().use { upstream ->
            val server = newServer(
                providers = listOf(
                    fakeProvider(
                        "github",
                        "GitHub Copilot",
                        upstream.baseUri,
                        "gh-token",
                        "gh-claude-sonnet-4.6",
                        "claude-sonnet-4.6",
                        routes = setOf(SubscriptionProxyRoute.ANTHROPIC_MESSAGES),
                    ),
                    fakeProvider("github2", "GitHub Copilot", upstream.baseUri, "gh-token", "gh-gpt-5.4", "gpt-5.4"),
                ),
            )
            try {
                server.start()

                val models = get(server.port, "/v1/models")
                val modelIds = parseObject(models.body())["data"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
                assertEquals(listOf("gh-gpt-5.4"), modelIds)

                val info = get(server.port, "/v1/model/info")
                val infoIds = parseObject(info.body())["data"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
                assertTrue("gh-claude-sonnet-4.6" in infoIds)
                assertTrue("gh-gpt-5.4" in infoIds)
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
    fun completionsDisabledReturns404() {
        TestUpstream().use { upstream ->
            val server = newServer(
                providers = listOf(fakeProvider("xai", "SuperGrok", upstream.baseUri, "grok-token", "grok-4.3", "grok-4.3")),
            )
            try {
                server.start()
                val response = post(server.port, "/v1/completions", "{\"model\":\"grok-4.3\",\"prompt\":\"fun \"}")
                assertEquals(404, response.statusCode())
                assertNull(upstream.requests.poll(500, TimeUnit.MILLISECONDS))
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun completionsAdvertisesAliasAndMapsChatToTextCompletion() {
        val chatBody =
            """{"id":"chatcmpl_1","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"a + b"},"finish_reason":"stop"}]}"""
        TestUpstream(responseBody = chatBody).use { upstream ->
            val server = newServer(
                providers = listOf(fakeProvider("xai", "SuperGrok", upstream.baseUri, "grok-token", "grok-4.3", "grok-4.3")),
                completionsConfig = CompletionsConfig(
                    enabled = true,
                    modelLocalId = "grok-4.3",
                    useChatAdapter = true,
                ),
            )
            try {
                server.start()
                val models = get(server.port, "/v1/models")
                val ids = parseObject(models.body())["data"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
                assertEquals(CompletionsConfig.FIM_ALIAS_ID, ids.first())
                assertTrue("grok-4.3" in ids)

                val response = post(
                    server.port,
                    "/v1/completions",
                    """{"model":"${CompletionsConfig.FIM_ALIAS_ID}","prompt":"fun add(a: Int, b: Int): Int {\n    return ","suffix":"\n}","stream":false}""",
                )
                assertEquals(200, response.statusCode(), response.body())
                val body = parseObject(response.body())
                assertEquals("text_completion", body["object"]!!.jsonPrimitive.content)
                val text = body["choices"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
                assertEquals("a + b", text)
                val upstreamRequest = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/v1/chat/completions", upstreamRequest.path)
                assertTrue(upstreamRequest.body.contains("code_before_cursor"), upstreamRequest.body)
                assertFalse(upstreamRequest.body.contains("<|fim_prefix|>"), upstreamRequest.body)
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun completionsRejectsModelNotAllowlisted() {
        TestUpstream().use { upstream ->
            val server = newServer(
                providers = listOf(fakeProvider("xai", "SuperGrok", upstream.baseUri, "grok-token", "grok-4.3", "grok-4.3")),
                completionsConfig = CompletionsConfig(enabled = true, modelLocalId = "grok-4.3"),
            )
            try {
                server.start()
                val response = post(server.port, "/completions", "{\"model\":\"other-model\",\"prompt\":\"fun \"}")
                assertEquals(400, response.statusCode())
                assertTrue(response.body().contains("Unknown proxy model"))
                assertNull(upstream.requests.poll(500, TimeUnit.MILLISECONDS))
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun nativeCompletionsPassThroughWithoutChatAdapter() {
        val completionBody =
            """{"id":"cmpl_1","object":"text_completion","choices":[{"index":0,"text":"a + b","finish_reason":"stop"}]}"""
        TestUpstream(responseBody = completionBody).use { upstream ->
            val server = newServer(
                providers = listOf(fakeProvider("ollama", "Ollama", upstream.baseUri, "ol-token", "ol-qwen", "qwen")),
                completionsConfig = CompletionsConfig(
                    enabled = true,
                    modelLocalId = "ol-qwen",
                    useChatAdapter = false,
                ),
            )
            try {
                server.start()
                val response = post(
                    server.port,
                    "/completions",
                    """{"model":"ol-qwen","prompt":"fun add() { return ","suffix":"}","stream":false}""",
                )
                assertEquals(200, response.statusCode(), response.body())
                val upstreamRequest = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/v1/completions", upstreamRequest.path)
                assertTrue(upstreamRequest.body.contains("\"model\":\"qwen\""), upstreamRequest.body)
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun completionsStreamMapsChatChunks() {
        val sse = "data: {\"id\":\"chatcmpl_1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"xyz\"},\"finish_reason\":null}]}\n\ndata: [DONE]\n\n"
        TestUpstream(responseBody = sse, responseContentType = "text/event-stream").use { upstream ->
            val server = newServer(
                providers = listOf(fakeProvider("xai", "SuperGrok", upstream.baseUri, "grok-token", "grok-4.3", "grok-4.3")),
                completionsConfig = CompletionsConfig(enabled = true, modelLocalId = "grok-4.3"),
            )
            try {
                server.start()
                val response = post(
                    server.port,
                    "/v1/completions",
                    """{"model":"grok-4.3","prompt":"abc","stream":true}""",
                )
                assertEquals(200, response.statusCode(), response.body())
                assertTrue(response.body().contains("\"object\":\"text_completion\""), response.body())
                assertTrue(response.body().contains("\"text\":\"xyz\""), response.body())
                assertTrue(response.body().contains("data: [DONE]"), response.body())
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
        routes: Set<SubscriptionProxyRoute> = setOf(SubscriptionProxyRoute.CHAT_COMPLETIONS, SubscriptionProxyRoute.RESPONSES),
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
                    supportedRoutes = routes,
                    isDefault = isDefault,
                ),
            ) },
            requestLogger = RequestLogger(false, Files.createTempDirectory("subscription-proxy-test-logs")),
        )
    }

    private fun newServer(
        providers: List<SubscriptionProxyProvider>,
        completionsConfig: CompletionsConfig = CompletionsConfig.DISABLED,
    ): TestServer {
        val port = freePort()
        return TestServer(
            port = port,
            server = SubscriptionProxyServer(
                port = port,
                localApiKeyProvider = { "local-key" },
                providers = { providers },
                completionsConfig = { completionsConfig },
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
