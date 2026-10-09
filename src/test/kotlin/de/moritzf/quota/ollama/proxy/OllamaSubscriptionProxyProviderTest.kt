package de.moritzf.quota.ollama.proxy

import com.sun.net.httpserver.HttpServer
import de.moritzf.proxy.fim.CompletionsConfig
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
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class OllamaSubscriptionProxyProviderTest {
    @Test
    fun advertisesOllamaModelsWithOlPrefixAndRewritesUpstreamModel() {
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
                        "ol-llama3.3",
                        "ol-gemma3:4b",
                        "ol-qwen3-coder-next",
                        "ol-deepseek-v4.1-flash",
                    ),
                    ids,
                )
                assertEquals(
                    "/v1/models",
                    assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS)).path,
                )

                val chatResponse =
                    post(
                        proxy.port,
                        "/v1/chat/completions",
                        "{\"model\":\"ol-llama3.3\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                    )

                assertEquals(200, chatResponse.statusCode())
                val chatRequest = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/v1/chat/completions", chatRequest.path)
                assertEquals("Bearer ollama-key", chatRequest.firstHeader("Authorization"))
                assertTrue(chatRequest.body.contains("\"model\":\"llama3.3\""), chatRequest.body)
            } finally {
                proxy.server.stop(gracePeriodMillis = 0)
            }
        }
    }

    @Test
    fun advertisesObservedOllamaToolSupportForJunieModelSelection() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.server.start()

                val response = get(proxy.port, "/v1/model/info")

                assertEquals(200, response.statusCode())
                val data =
                    JsonHelper.JSON.parseToJsonElement(response.body())
                        .jsonObject["data"]!!
                        .jsonArray
                val gemmaInfo =
                    data
                        .first { it.jsonObject["id"]!!.jsonPrimitive.content == "ol-gemma3:4b" }
                        .jsonObject["model_info"]!!
                        .jsonObject
                val qwenInfo =
                    data
                        .first {
                            it.jsonObject["id"]!!.jsonPrimitive.content == "ol-qwen3-coder-next"
                        }
                        .jsonObject["model_info"]!!
                        .jsonObject
                assertFalse(
                    gemmaInfo["supports_function_calling"]!!.jsonPrimitive.content.toBoolean()
                )
                assertFalse(gemmaInfo["supports_tool_choice"]!!.jsonPrimitive.content.toBoolean())
                assertTrue(
                    qwenInfo["supports_function_calling"]!!.jsonPrimitive.content.toBoolean()
                )
                assertTrue(qwenInfo["supports_tool_choice"]!!.jsonPrimitive.content.toBoolean())
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
                        "{\"model\":\"ol-llama4\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                    )

                assertEquals(200, chatResponse.statusCode())
                assertEquals(
                    "/v1/models",
                    assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS)).path,
                )
                val chatRequest = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/v1/chat/completions", chatRequest.path)
                assertTrue(chatRequest.body.contains("\"model\":\"llama4\""), chatRequest.body)
                assertFalse(chatRequest.body.contains("ol-llama4"), chatRequest.body)
            } finally {
                proxy.server.stop(gracePeriodMillis = 0)
            }
        }
    }

    @Test
    fun mapsOpenAiV1BaseToGenerateUrl() {
        assertEquals(
            "https://ollama.com/api/generate",
            OllamaSubscriptionProxyProvider.generateUrl(URI.create("https://ollama.com/v1")),
        )
        assertEquals(
            "http://127.0.0.1:11434/api/generate",
            OllamaSubscriptionProxyProvider.generateUrl(URI.create("http://127.0.0.1:11434/v1")),
        )
    }

    @Test
    fun convertsCompletionsBodyToGenerateRequest() {
        val body =
            OllamaSubscriptionProxyProvider.toGenerateRequest(
                buildJsonObject {
                    put("model", "qwen3-coder-next")
                    put("prompt", "fun add() {\n    return ")
                    put("suffix", "\n}")
                    put("max_tokens", 48)
                    put("stream", true)
                }
            )
        assertEquals("qwen3-coder-next", body.jsonObject["model"]!!.jsonPrimitive.content)
        assertEquals("fun add() {\n    return ", body.jsonObject["prompt"]!!.jsonPrimitive.content)
        assertEquals("\n}", body.jsonObject["suffix"]!!.jsonPrimitive.content)
        assertFalse(body.jsonObject["stream"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(
            48,
            body.jsonObject["options"]!!.jsonObject["num_predict"]!!.jsonPrimitive.content.toInt(),
        )
    }

    @Test
    fun routesNativeCompletionsToGenerate() {
        TestUpstream().use { upstream ->
            val proxy =
                newProxy(
                    upstream.baseUri,
                    completionsConfig =
                        CompletionsConfig(
                            enabled = true,
                            modelLocalId = "ol-qwen3-coder-next",
                            useChatAdapter = false,
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
                val generate = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/api/generate", generate.path)
                assertTrue(generate.body.contains("\"prompt\""), generate.body)
                assertTrue(generate.body.contains("\"suffix\""), generate.body)
                assertTrue(response.body().contains("\"text\""), response.body())
                assertTrue(response.body().contains("a + b"), response.body())
            } finally {
                proxy.server.stop(gracePeriodMillis = 0)
            }
        }
    }

    @Test
    fun convertsDeepSeekFlashCompletionsToRawFimWithoutThinking() {
        for (model in listOf("deepseek-v4.1-flash", "deepseek-v4.1-flash:cloud")) {
            for (suffix in listOf("\n}", "")) {
                val body =
                    OllamaSubscriptionProxyProvider.toGenerateRequest(
                        buildJsonObject {
                            put("model", model)
                            put("prompt", "return ")
                            put("suffix", suffix)
                            put("max_tokens", 48)
                            put("temperature", 0.2)
                            put("stop", "\n")
                        }
                    )
                assertEquals(model, body["model"]!!.jsonPrimitive.content)
                assertEquals(
                    "<｜fim▁begin｜>return <｜fim▁hole｜>$suffix<｜fim▁end｜>",
                    body["prompt"]!!.jsonPrimitive.content,
                )
                assertEquals("true", body["raw"]!!.jsonPrimitive.content)
                assertEquals("false", body["think"]!!.jsonPrimitive.content)
                assertEquals("false", body["stream"]!!.jsonPrimitive.content)
                assertFalse("suffix" in body)
                val options = body["options"]!!.jsonObject
                assertEquals("48", options["num_predict"]!!.jsonPrimitive.content)
                assertEquals("0.2", options["temperature"]!!.jsonPrimitive.content)
                assertEquals("\n", options["stop"]!!.jsonPrimitive.content)
            }
        }
    }

    @Test
    fun leavesUnverifiedDeepSeekModelsOnOrdinaryGeneratePath() {
        for (model in
            listOf("deepseek-v4-pro:0813", "deepseek-v3.2", "deepseek-v4.1-flash-other")) {
            val body =
                OllamaSubscriptionProxyProvider.toGenerateRequest(
                    buildJsonObject {
                        put("model", model)
                        put("prompt", "return ")
                        put("suffix", "\n}")
                    }
                )
            assertEquals("return ", body["prompt"]!!.jsonPrimitive.content)
            assertEquals("\n}", body["suffix"]!!.jsonPrimitive.content)
            assertFalse("raw" in body)
            assertFalse("think" in body)
        }
    }

    @Test
    fun capsDeepSeekUpstreamStopsButKeepsOtherModelsUnchanged() {
        val stops = (1..6).map { "stop-$it" }
        for (model in listOf("deepseek-v4.1-flash", "deepseek-v4.1-flash:cloud", "qwen2.5-coder")) {
            val body =
                OllamaSubscriptionProxyProvider.toGenerateRequest(
                    buildJsonObject {
                        put("model", model)
                        put("prompt", "return ")
                        put("stop", buildJsonArray { stops.forEach { add(JsonPrimitive(it)) } })
                    }
                )
            val sent =
                body["options"]!!.jsonObject["stop"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(
                if (model.startsWith("deepseek-v4.1-flash")) stops.take(4) else stops,
                sent,
            )
        }
    }

    @Test
    fun enforcesStopsOmittedFromUpstreamLocally() {
        val raw = """{"model":"deepseek-v4.1-flash","response":"a + bstop-5unwanted"}"""
        val result =
            OllamaSubscriptionProxyProvider.toTextCompletion(raw, (1..6).map { "stop-$it" })
        assertEquals(
            "a + b",
            JsonHelper.JSON.parseToJsonElement(result)
                .jsonObject["choices"]!!
                .jsonArray[0]
                .jsonObject["text"]!!
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun advertisesAndRoutesDeepSeekNativeFimForJsonAndSse() {
        for (stream in listOf(false, true)) {
            TestUpstream().use { upstream ->
                val proxy =
                    newProxy(
                        upstream.baseUri,
                        CompletionsConfig(
                            enabled = true,
                            modelLocalId = "ol-deepseek-v4.1-flash",
                            useChatAdapter = false,
                        ),
                    )
                try {
                    proxy.server.start()
                    val info = get(proxy.port, "/v1/model/info")
                    val modelInfo =
                        JsonHelper.JSON.parseToJsonElement(info.body())
                            .jsonObject["data"]!!
                            .jsonArray
                            .first {
                                it.jsonObject["id"]!!.jsonPrimitive.content ==
                                    "ol-deepseek-v4.1-flash"
                            }
                            .jsonObject["model_info"]!!
                            .jsonObject
                    assertEquals("true", modelInfo["supports_native_fim"]!!.jsonPrimitive.content)
                    assertEquals("native", modelInfo["fim_mode"]!!.jsonPrimitive.content)
                    assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))

                    val response =
                        post(
                            proxy.port,
                            "/v1/completions",
                            JsonHelper.encodeToString(
                                buildJsonObject {
                                    put("model", "qwen2.5-coder")
                                    put("prompt", "<｜fim▁begin｜>return <｜fim▁hole｜>\n}<｜fim▁end｜>")
                                    put("stream", stream)
                                    put("max_tokens", 48)
                                }
                            ),
                        )
                    assertEquals(200, response.statusCode(), response.body())
                    val generate = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                    assertEquals("/api/generate", generate.path)
                    assertEquals("Bearer ollama-key", generate.firstHeader("Authorization"))
                    val body = JsonHelper.JSON.parseToJsonElement(generate.body).jsonObject
                    assertEquals("deepseek-v4.1-flash", body["model"]!!.jsonPrimitive.content)
                    assertEquals(
                        "<｜fim▁begin｜>return <｜fim▁hole｜>\n}<｜fim▁end｜>",
                        body["prompt"]!!.jsonPrimitive.content,
                    )
                    assertEquals("true", body["raw"]!!.jsonPrimitive.content)
                    assertEquals("false", body["think"]!!.jsonPrimitive.content)
                    assertEquals("false", body["stream"]!!.jsonPrimitive.content)
                    assertFalse("suffix" in body)
                    assertEquals(4, body["options"]!!.jsonObject["stop"]!!.jsonArray.size)
                    assertTrue(response.body().contains("a + b"), response.body())
                    if (stream) {
                        assertTrue(
                            response
                                .headers()
                                .firstValue("Content-Type")
                                .orElse("")
                                .startsWith("text/event-stream")
                        )
                        assertTrue(response.body().contains("data: [DONE]"), response.body())
                    } else {
                        val text =
                            JsonHelper.JSON.parseToJsonElement(response.body())
                                .jsonObject["choices"]!!
                                .jsonArray[0]
                                .jsonObject["text"]!!
                                .jsonPrimitive
                                .content
                        assertEquals("a + b", text)
                    }
                } finally {
                    proxy.server.stop(gracePeriodMillis = 0)
                }
            }
        }
    }

    private fun newProxy(
        upstreamBaseUri: URI,
        completionsConfig: CompletionsConfig = CompletionsConfig.DISABLED,
    ): TestProxy {
        val port = freePort()
        val provider =
            OllamaSubscriptionProxyProvider(
                apiKeyProvider = { "ollama-key" },
                upstreamBaseUri = upstreamBaseUri,
                requestLogDir =
                    Files.createTempDirectory("ollama-subscription-proxy-test-logs").toString(),
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

    private class TestUpstream : AutoCloseable {
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
                val responseBody =
                    if (path.endsWith("/models")) {
                        "{\"object\":\"list\",\"data\":[" +
                            "{\"id\":\"llama3.3\",\"object\":\"model\"}," +
                            "{\"id\":\"gemma3:4b\",\"object\":\"model\"}," +
                            "{\"id\":\"qwen3-coder-next\",\"object\":\"model\"}," +
                            "{\"id\":\"deepseek-v4.1-flash\",\"object\":\"model\"}," +
                            "{\"id\":\"nomic-embed-text\",\"object\":\"embedding\"}" +
                            "]}"
                    } else if (path.endsWith("/api/generate")) {
                        "{\"model\":\"qwen3-coder-next\",\"response\":\"a + b\",\"done\":true}"
                    } else {
                        "{\"id\":\"chatcmpl_1\",\"choices\":[]}"
                    }
                val response = responseBody.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json")
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
