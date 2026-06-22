package de.moritzf.quota.ollama.proxy

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
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OllamaSubscriptionProxyProviderTest {
    @Test
    fun advertisesOllamaModelsWithOlPrefixAndRewritesUpstreamModel() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.server.start()

                val modelsResponse = get(proxy.port, "/v1/models")
                assertEquals(200, modelsResponse.statusCode())
                val ids = JsonHelper.JSON.parseToJsonElement(modelsResponse.body()).jsonObject["data"]!!.jsonArray
                    .map { it.jsonObject["id"]!!.jsonPrimitive.content }
                assertEquals(listOf("ol-llama3.3", "ol-gemma3:4b", "ol-qwen3-coder-next"), ids)
                assertEquals("/v1/models", assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS)).path)

                val chatResponse = post(
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
                proxy.server.stop()
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
                val data = JsonHelper.JSON.parseToJsonElement(response.body()).jsonObject["data"]!!.jsonArray
                val gemmaInfo = data.first { it.jsonObject["id"]!!.jsonPrimitive.content == "ol-gemma3:4b" }
                    .jsonObject["model_info"]!!.jsonObject
                val qwenInfo = data.first { it.jsonObject["id"]!!.jsonPrimitive.content == "ol-qwen3-coder-next" }
                    .jsonObject["model_info"]!!.jsonObject
                assertFalse(gemmaInfo["supports_function_calling"]!!.jsonPrimitive.content.toBoolean())
                assertFalse(gemmaInfo["supports_tool_choice"]!!.jsonPrimitive.content.toBoolean())
                assertTrue(qwenInfo["supports_function_calling"]!!.jsonPrimitive.content.toBoolean())
                assertTrue(qwenInfo["supports_tool_choice"]!!.jsonPrimitive.content.toBoolean())
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
                    "{\"model\":\"ol-llama4\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                )

                assertEquals(200, chatResponse.statusCode())
                assertEquals("/v1/models", assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS)).path)
                val chatRequest = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/v1/chat/completions", chatRequest.path)
                assertTrue(chatRequest.body.contains("\"model\":\"llama4\""), chatRequest.body)
                assertFalse(chatRequest.body.contains("ol-llama4"), chatRequest.body)
            } finally {
                proxy.server.stop()
            }
        }
    }

    private fun newProxy(upstreamBaseUri: URI): TestProxy {
        val port = freePort()
        val provider = OllamaSubscriptionProxyProvider(
            apiKeyProvider = { "ollama-key" },
            upstreamBaseUri = upstreamBaseUri,
            requestLogDir = Files.createTempDirectory("ollama-subscription-proxy-test-logs").toString(),
        )
        return TestProxy(
            port,
            SubscriptionProxyServer(
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
                val responseBody = if (exchange.requestURI.rawPath.endsWith("/models")) {
                    "{\"object\":\"list\",\"data\":[" +
                        "{\"id\":\"llama3.3\",\"object\":\"model\"}," +
                        "{\"id\":\"gemma3:4b\",\"object\":\"model\"}," +
                        "{\"id\":\"qwen3-coder-next\",\"object\":\"model\"}," +
                        "{\"id\":\"nomic-embed-text\",\"object\":\"embedding\"}" +
                        "]}"
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
