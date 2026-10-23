package de.moritzf.quota.mistral.proxy

import com.sun.net.httpserver.HttpServer
import de.moritzf.proxy.fim.CompletionsConfig
import de.moritzf.proxy.media.OpenAiMedia
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MistralSubscriptionProxyProviderTest {
    @Test
    fun advertisesMistralModelsWithMiPrefixAndRewritesUpstreamModel() {
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
                assertEquals(
                    listOf(
                        "mi-mistral-small-latest",
                        "mi-codestral-latest",
                        "mi-mistral-medium-3-5",
                        "mi-zai-glm-5-3",
                        "mi-ministral-8b-latest",
                    ),
                    ids.filter { it !in OpenAiMedia.advertisedMediaIds(setOf("mistral")) },
                )
                assertTrue("mi-voxtral-mini-tts-3-0" in ids)
                assertEquals(
                    "/v1/models",
                    assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS)).path,
                )

                val chatResponse =
                    post(
                        proxy.port,
                        "/v1/chat/completions",
                        "{\"model\":\"mi-mistral-small-latest\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                    )

                assertEquals(200, chatResponse.statusCode())
                val chatRequest = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/v1/chat/completions", chatRequest.path)
                assertEquals("Bearer mistral-key", chatRequest.firstHeader("Authorization"))
                assertTrue(
                    chatRequest.body.contains("\"model\":\"mistral-small-latest\""),
                    chatRequest.body,
                )
            } finally {
                proxy.server.stop(gracePeriodMillis = 0)
            }
        }
    }

    @Test
    fun forwardsPrefixedModelsMissingFromDiscovery() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.server.start()

                val chatResponse =
                    post(
                        proxy.port,
                        "/v1/chat/completions",
                        "{\"model\":\"mi-mistral-large-latest\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                    )

                assertEquals(200, chatResponse.statusCode())
                assertEquals(
                    "/v1/models",
                    assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS)).path,
                )
                val chatRequest = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/v1/chat/completions", chatRequest.path)
                assertTrue(
                    chatRequest.body.contains("\"model\":\"mistral-large-latest\""),
                    chatRequest.body,
                )
                assertTrue(!chatRequest.body.contains("mi-mistral-large-latest"), chatRequest.body)
            } finally {
                proxy.server.stop(gracePeriodMillis = 0)
            }
        }
    }

    @Test
    fun dropsJunieLiteLlmExtrasAndReasoningEffortBeforeUpstream() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.server.start()
                get(proxy.port, "/v1/models")
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))

                val response =
                    post(
                        proxy.port,
                        "/v1/chat/completions",
                        "{" +
                            "\"model\":\"mi-codestral-latest\"," +
                            "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]," +
                            "\"user\":\"capability_filter\"," +
                            "\"seed\":100000," +
                            "\"drop_params\":true," +
                            "\"thinking\":{\"type\":\"enabled\"}," +
                            "\"reasoning_effort\":\"medium\"," +
                            "\"temperature\":0.0" +
                            "}",
                    )

                assertEquals(200, response.statusCode(), response.body())
                val chatRequest = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/v1/chat/completions", chatRequest.path)
                assertTrue(
                    chatRequest.body.contains("\"model\":\"codestral-latest\""),
                    chatRequest.body,
                )
                assertTrue(chatRequest.body.contains("\"temperature\""), chatRequest.body)
                assertTrue(!chatRequest.body.contains("drop_params"), chatRequest.body)
                assertTrue(!chatRequest.body.contains("capability_filter"), chatRequest.body)
                assertTrue(!chatRequest.body.contains("\"seed\""), chatRequest.body)
                assertTrue(!chatRequest.body.contains("thinking"), chatRequest.body)
                assertTrue(!chatRequest.body.contains("reasoning_effort"), chatRequest.body)
            } finally {
                proxy.server.stop(gracePeriodMillis = 0)
            }
        }
    }

    @Test
    fun forwardsReasoningEffortToReasoningModelsAndReturnsThinkingAsReasoningContent() {
        val chunked =
            "{\"id\":\"chatcmpl_1\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0," +
                "\"message\":{\"role\":\"assistant\",\"tool_calls\":null,\"content\":[" +
                "{\"type\":\"thinking\",\"thinking\":[{\"type\":\"text\",\"text\":\"17*23 is 391.\"}]}," +
                "{\"type\":\"text\",\"text\":\"391\"}]},\"finish_reason\":\"stop\"}]}"
        TestUpstream(chatReply = { "application/json" to chunked }).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.server.start()
                get(proxy.port, "/v1/models")
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))

                fun sentEffort(model: String, effort: String): String? {
                    val response =
                        post(
                            proxy.port,
                            "/v1/chat/completions",
                            """{"model":"$model","messages":[{"role":"user","content":"17*23?"}],"reasoning_effort":"$effort"}""",
                        )
                    assertEquals(200, response.statusCode(), response.body())
                    val sent = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                    return JsonHelper.JSON.parseToJsonElement(sent.body)
                        .jsonObject["reasoning_effort"]
                        ?.jsonPrimitive
                        ?.content
                }
                // Mistral models switch reasoning on or off; GLM 5.3 has low/high/max.
                assertEquals("high", sentEffort("mi-mistral-medium-3-5", "high"))
                assertEquals("high", sentEffort("mi-mistral-medium-3-5", "medium"))
                assertEquals("none", sentEffort("mi-mistral-medium-3-5", "low"))
                assertEquals("low", sentEffort("mi-zai-glm-5-3", "none"))
                assertEquals("high", sentEffort("mi-zai-glm-5-3", "xhigh"))
                assertEquals("max", sentEffort("mi-zai-glm-5-3", "max"))
                assertNull(sentEffort("mi-ministral-8b-latest", "high"))

                val response =
                    post(
                        proxy.port,
                        "/v1/chat/completions",
                        """{"model":"mi-mistral-medium-3-5","messages":[{"role":"user","content":"17*23?"}],"reasoning_effort":"high"}""",
                    )
                assertEquals(200, response.statusCode(), response.body())
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                val message =
                    JsonHelper.JSON.parseToJsonElement(response.body())
                        .jsonObject["choices"]!!
                        .jsonArray[0]
                        .jsonObject["message"]!!
                        .jsonObject
                assertEquals("391", message["content"]!!.jsonPrimitive.content)
                assertEquals("17*23 is 391.", message["reasoning_content"]!!.jsonPrimitive.content)
            } finally {
                proxy.server.stop(gracePeriodMillis = 0)
            }
        }
    }

    @Test
    fun streamsThinkingChunksAsReasoningContentDeltas() {
        val stream =
            "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":[{\"type\":\"text\",\"text\":\"think\"}]}]}}]}\n\n" +
                "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":[{\"type\":\"thinking\",\"thinking\":[{\"type\":\"text\",\"text\":\"ing\"}],\"closed\":true},{\"type\":\"text\",\"text\":\"39\"}]}}]}\n\n" +
                "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"1\"},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: [DONE]\n\n"
        TestUpstream(chatReply = { "text/event-stream" to stream }).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.server.start()
                get(proxy.port, "/v1/models")
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))

                val response =
                    post(
                        proxy.port,
                        "/v1/chat/completions",
                        """{"model":"mi-mistral-medium-3-5","stream":true,"messages":[{"role":"user","content":"17*23?"}],"reasoning_effort":"high"}""",
                    )
                assertEquals(200, response.statusCode(), response.body())
                val deltas =
                    response
                        .body()
                        .lines()
                        .filter { it.startsWith("data: {") }
                        .map {
                            JsonHelper.JSON.parseToJsonElement(it.removePrefix("data: "))
                                .jsonObject["choices"]!!
                                .jsonArray[0]
                                .jsonObject["delta"]!!
                                .jsonObject
                        }
                assertEquals(
                    "thinking",
                    deltas.joinToString("") {
                        it["reasoning_content"]?.jsonPrimitive?.content.orEmpty()
                    },
                )
                assertEquals(
                    "391",
                    deltas.joinToString("") { it["content"]?.jsonPrimitive?.content.orEmpty() },
                )
                assertTrue(response.body().contains("data: [DONE]"), response.body())
            } finally {
                proxy.server.stop(gracePeriodMillis = 0)
            }
        }
    }

    @Test
    fun replaysReasoningContentAsThinkingChunkOnlyForReasoningModels() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.server.start()
                get(proxy.port, "/v1/models")
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))

                fun sentAssistant(model: String): JsonObject {
                    val response =
                        post(
                            proxy.port,
                            "/v1/chat/completions",
                            """{"model":"$model","messages":[{"role":"user","content":"17*23?"},{"role":"assistant","content":"391","reasoning_content":"17*23 is 391."},{"role":"user","content":"times 3?"}]}""",
                        )
                    assertEquals(200, response.statusCode(), response.body())
                    val sent = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                    return JsonHelper.JSON.parseToJsonElement(sent.body)
                        .jsonObject["messages"]!!
                        .jsonArray[1]
                        .jsonObject
                }

                val replayed = sentAssistant("mi-mistral-medium-3-5")
                assertTrue("reasoning_content" !in replayed, replayed.toString())
                val chunks = replayed["content"]!!.jsonArray
                assertEquals("thinking", chunks[0].jsonObject["type"]!!.jsonPrimitive.content)
                assertEquals(
                    "17*23 is 391.",
                    chunks[0]
                        .jsonObject["thinking"]!!
                        .jsonArray[0]
                        .jsonObject["text"]!!
                        .jsonPrimitive
                        .content,
                )
                assertEquals("391", chunks[1].jsonObject["text"]!!.jsonPrimitive.content)

                val stripped = sentAssistant("mi-ministral-8b-latest")
                assertTrue("reasoning_content" !in stripped, stripped.toString())
                assertEquals("391", stripped["content"]!!.jsonPrimitive.content)
            } finally {
                proxy.server.stop(gracePeriodMillis = 0)
            }
        }
    }

    @Test
    fun advertisesNativeFimOnCodestralAndKeepsChatModelsNone() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.server.start()
                get(proxy.port, "/v1/models")
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))

                val response = get(proxy.port, "/v1/model/info")
                assertEquals(200, response.statusCode())
                val byName =
                    JsonHelper.JSON.parseToJsonElement(response.body())
                        .jsonObject["data"]!!
                        .jsonArray
                        .associate { row ->
                            val obj = row.jsonObject
                            obj["model_name"]!!.jsonPrimitive.content to
                                obj["model_info"]!!.jsonObject
                        }
                val codestral = assertNotNull(byName["mi-codestral-latest"])
                assertEquals("native", codestral["fim_mode"]!!.jsonPrimitive.content)
                assertTrue(codestral["supports_native_fim"]!!.jsonPrimitive.content.toBoolean())
                val small = assertNotNull(byName["mi-mistral-small-latest"])
                assertEquals("none", small["fim_mode"]!!.jsonPrimitive.content)
                assertTrue(!small["supports_native_fim"]!!.jsonPrimitive.content.toBoolean())
            } finally {
                proxy.server.stop(gracePeriodMillis = 0)
            }
        }
    }

    @Test
    fun convertsChatShapedFimReplyToTextCompletion() {
        val raw =
            "{\"id\":\"fim_1\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"a + b\"},\"finish_reason\":\"stop\"}]}"
        val converted = MistralSubscriptionProxyProvider.toTextCompletion(raw)
        assertTrue(converted.contains("\"text\":\"a + b\""), converted)
        assertTrue(converted.contains("\"object\":\"text_completion\""), converted)
        assertTrue(!converted.contains("\"message\""), converted)
    }

    @Test
    fun routesNativeCompletionsToMistralFimEndpoint() {
        TestUpstream().use { upstream ->
            val proxy =
                newProxy(
                    upstream.baseUri,
                    completionsConfig =
                        CompletionsConfig(
                            enabled = true,
                            modelLocalId = "mi-codestral-latest",
                        ),
                )
            try {
                proxy.server.start()
                get(proxy.port, "/v1/models")
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))

                val response =
                    post(
                        proxy.port,
                        "/v1/completions",
                        "{\"model\":\"qwen2.5-coder\",\"prompt\":\"fun add(a: Int, b: Int): Int {\\n    return \",\"suffix\":\"\\n}\\n\",\"stream\":false,\"max_tokens\":48}",
                    )

                assertEquals(200, response.statusCode(), response.body())
                val fimRequest = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/v1/fim/completions", fimRequest.path)
                assertTrue(
                    fimRequest.body.contains("\"model\":\"codestral-latest\""),
                    fimRequest.body,
                )
                assertTrue(fimRequest.body.contains("\"prompt\""), fimRequest.body)
                assertTrue(!fimRequest.body.contains("\"messages\""), fimRequest.body)
                val prompt =
                    JsonHelper.JSON.parseToJsonElement(fimRequest.body)
                        .jsonObject["prompt"]!!
                        .jsonPrimitive
                        .content
                assertTrue(!prompt.contains("<|fim_"), prompt)
                assertTrue(response.body().contains("\"text\""), response.body())
                assertTrue(response.body().contains("a + b"), response.body())
            } finally {
                proxy.server.stop(gracePeriodMillis = 0)
            }
        }
    }

    private fun newProxy(
        upstreamBaseUri: URI,
        completionsConfig: CompletionsConfig = CompletionsConfig.DISABLED,
    ): TestProxy {
        val port = freePort()
        val provider =
            MistralSubscriptionProxyProvider(
                apiKeyProvider = { "mistral-key" },
                upstreamBaseUri = upstreamBaseUri,
                requestLogDir =
                    Files.createTempDirectory("mistral-subscription-proxy-test-logs").toString(),
            )
        return TestProxy(
            port,
            SubscriptionProxyServer(
                port = port,
                localApiKeyProvider = { "local-key" },
                providers = { listOf(provider) },
                requestLogDir =
                    Files.createTempDirectory("subscription-proxy-test-logs").toString(),
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

    private data class TestProxy(val port: Int, val server: SubscriptionProxyServer)

    private class TestUpstream(
        private val chatReply: (String) -> Pair<String, String> = {
            "application/json" to "{\"id\":\"chatcmpl_1\",\"choices\":[]}"
        }
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
                        path = exchange.requestURI.rawPath,
                        headers = exchange.requestHeaders.mapValues { it.value.toList() },
                        body = body,
                    )
                val path = exchange.requestURI.rawPath
                val (contentType, responseBody) =
                    if (path.endsWith("/models")) {
                        "application/json" to
                            "{\"object\":\"list\",\"data\":[" +
                                "{\"id\":\"mistral-small-latest\",\"object\":\"model\"}," +
                                "{\"id\":\"codestral-latest\",\"object\":\"model\"}," +
                                "{\"id\":\"voxtral-mini-latest\",\"object\":\"model\"}," +
                                "{\"id\":\"mistral-embed\",\"object\":\"embedding\"}," +
                                "{\"id\":\"mistral-medium-3-5\",\"object\":\"model\",\"capabilities\":{\"completion_chat\":true,\"reasoning\":true}}," +
                                "{\"id\":\"zai-glm-5-3\",\"object\":\"model\",\"capabilities\":{\"completion_chat\":true,\"reasoning\":true}}," +
                                "{\"id\":\"ministral-8b-latest\",\"object\":\"model\",\"capabilities\":{\"completion_chat\":true,\"reasoning\":false}}" +
                                "]}"
                    } else if (path.endsWith("/fim/completions")) {
                        "application/json" to
                            "{\"id\":\"fim_1\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"a + b\"},\"finish_reason\":\"stop\"}]}"
                    } else {
                        chatReply(body)
                    }
                val response = responseBody.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", contentType)
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
