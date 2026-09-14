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
import kotlin.test.assertTrue
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
                val ids = JsonHelper.JSON.parseToJsonElement(modelsResponse.body()).jsonObject["data"]!!.jsonArray
                    .map { it.jsonObject["id"]!!.jsonPrimitive.content }
                assertEquals(
                    listOf("mi-mistral-small-latest", "mi-codestral-latest"),
                    ids.filter { it !in OpenAiMedia.advertisedMediaIds(setOf("mistral")) },
                )
                assertTrue("mi-voxtral-mini-tts-2603" in ids)
                assertEquals("/v1/models", assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS)).path)

                val chatResponse = post(
                    proxy.port,
                    "/v1/chat/completions",
                    "{\"model\":\"mi-mistral-small-latest\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                )

                assertEquals(200, chatResponse.statusCode())
                val chatRequest = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/v1/chat/completions", chatRequest.path)
                assertEquals("Bearer mistral-key", chatRequest.firstHeader("Authorization"))
                assertTrue(chatRequest.body.contains("\"model\":\"mistral-small-latest\""), chatRequest.body)
            } finally {
                proxy.server.stop()
            }
        }
    }

    @Test
    fun forwardsPrefixedModelsMissingFromDiscovery() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.server.start()

                val chatResponse = post(
                    proxy.port,
                    "/v1/chat/completions",
                    "{\"model\":\"mi-mistral-large-latest\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                )

                assertEquals(200, chatResponse.statusCode())
                assertEquals("/v1/models", assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS)).path)
                val chatRequest = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/v1/chat/completions", chatRequest.path)
                assertTrue(chatRequest.body.contains("\"model\":\"mistral-large-latest\""), chatRequest.body)
                assertTrue(!chatRequest.body.contains("mi-mistral-large-latest"), chatRequest.body)
            } finally {
                proxy.server.stop()
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

                val response = post(
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
                assertTrue(chatRequest.body.contains("\"model\":\"codestral-latest\""), chatRequest.body)
                assertTrue(chatRequest.body.contains("\"temperature\""), chatRequest.body)
                assertTrue(!chatRequest.body.contains("drop_params"), chatRequest.body)
                assertTrue(!chatRequest.body.contains("capability_filter"), chatRequest.body)
                assertTrue(!chatRequest.body.contains("\"seed\""), chatRequest.body)
                assertTrue(!chatRequest.body.contains("thinking"), chatRequest.body)
                assertTrue(!chatRequest.body.contains("reasoning_effort"), chatRequest.body)
            } finally {
                proxy.server.stop()
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
                val byName = JsonHelper.JSON.parseToJsonElement(response.body()).jsonObject["data"]!!.jsonArray
                    .associate { row ->
                        val obj = row.jsonObject
                        obj["model_name"]!!.jsonPrimitive.content to obj["model_info"]!!.jsonObject
                    }
                val codestral = assertNotNull(byName["mi-codestral-latest"])
                assertEquals("native", codestral["fim_mode"]!!.jsonPrimitive.content)
                assertTrue(codestral["supports_native_fim"]!!.jsonPrimitive.content.toBoolean())
                val small = assertNotNull(byName["mi-mistral-small-latest"])
                assertEquals("none", small["fim_mode"]!!.jsonPrimitive.content)
                assertTrue(!small["supports_native_fim"]!!.jsonPrimitive.content.toBoolean())
            } finally {
                proxy.server.stop()
            }
        }
    }

    @Test
    fun convertsChatShapedFimReplyToTextCompletion() {
        val raw = "{\"id\":\"fim_1\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"a + b\"},\"finish_reason\":\"stop\"}]}"
        val converted = MistralSubscriptionProxyProvider.toTextCompletion(raw)
        assertTrue(converted.contains("\"text\":\"a + b\""), converted)
        assertTrue(converted.contains("\"object\":\"text_completion\""), converted)
        assertTrue(!converted.contains("\"message\""), converted)
    }

    @Test
    fun routesNativeCompletionsToMistralFimEndpoint() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(
                upstream.baseUri,
                completionsConfig = CompletionsConfig(
                    enabled = true,
                    modelLocalId = "mi-codestral-latest",
                    useChatAdapter = false,
                ),
            )
            try {
                proxy.server.start()
                get(proxy.port, "/v1/models")
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))

                val response = post(
                    proxy.port,
                    "/v1/completions",
                    "{\"model\":\"qwen2.5-coder\",\"prompt\":\"fun add(a: Int, b: Int): Int {\\n    return \",\"suffix\":\"\\n}\\n\",\"stream\":false,\"max_tokens\":48}",
                )

                assertEquals(200, response.statusCode(), response.body())
                val fimRequest = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/v1/fim/completions", fimRequest.path)
                assertTrue(fimRequest.body.contains("\"model\":\"codestral-latest\""), fimRequest.body)
                assertTrue(fimRequest.body.contains("\"prompt\""), fimRequest.body)
                assertTrue(!fimRequest.body.contains("\"messages\""), fimRequest.body)
                val prompt = JsonHelper.JSON.parseToJsonElement(fimRequest.body).jsonObject["prompt"]!!.jsonPrimitive.content
                assertTrue(!prompt.contains("<|fim_"), prompt)
                assertTrue(response.body().contains("\"text\""), response.body())
                assertTrue(response.body().contains("a + b"), response.body())
            } finally {
                proxy.server.stop()
            }
        }
    }

    private fun newProxy(
        upstreamBaseUri: URI,
        completionsConfig: CompletionsConfig = CompletionsConfig.DISABLED,
    ): TestProxy {
        val port = freePort()
        val provider = MistralSubscriptionProxyProvider(
            apiKeyProvider = { "mistral-key" },
            upstreamBaseUri = upstreamBaseUri,
            requestLogDir = Files.createTempDirectory("mistral-subscription-proxy-test-logs").toString(),
        )
        return TestProxy(
            port,
            SubscriptionProxyServer(
                port = port,
                localApiKeyProvider = { "local-key" },
                providers = { listOf(provider) },
                requestLogDir = Files.createTempDirectory("subscription-proxy-test-logs").toString(),
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
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val baseUri: URI

        init {
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                requests += CapturedRequest(
                    path = exchange.requestURI.rawPath,
                    headers = exchange.requestHeaders.mapValues { it.value.toList() },
                    body = body,
                )
                val path = exchange.requestURI.rawPath
                val responseBody = if (path.endsWith("/models")) {
                    "{\"object\":\"list\",\"data\":[" +
                        "{\"id\":\"mistral-small-latest\",\"object\":\"model\"}," +
                        "{\"id\":\"codestral-latest\",\"object\":\"model\"}," +
                        "{\"id\":\"voxtral-mini-latest\",\"object\":\"model\"}," +
                        "{\"id\":\"mistral-embed\",\"object\":\"embedding\"}" +
                        "]}"
                } else if (path.endsWith("/fim/completions")) {
                    "{\"id\":\"fim_1\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"a + b\"},\"finish_reason\":\"stop\"}]}"
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
    }
}
