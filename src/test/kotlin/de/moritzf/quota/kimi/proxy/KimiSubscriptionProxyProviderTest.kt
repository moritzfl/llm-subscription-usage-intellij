package de.moritzf.quota.kimi.proxy

import com.sun.net.httpserver.HttpServer
import de.moritzf.proxy.server.JsonHelper
import de.moritzf.proxy.subscription.SubscriptionProxyServer
import de.moritzf.quota.kimi.KimiCredentials
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

class KimiSubscriptionProxyProviderTest {
    @Test
    fun advertisesDiscoveredKimiCodeModelsFromOAuthCredentials() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream)
            try {
                proxy.server.start()
                val response = get(proxy.port, "/v1/models")

                assertEquals(200, response.statusCode())
                val ids = JsonHelper.JSON.parseToJsonElement(response.body()).jsonObject["data"]!!.jsonArray
                    .map { it.jsonObject["id"]!!.jsonPrimitive.content }
                assertEquals(listOf("ki-kimi-for-coding", "ki-kimi-k2.5"), ids)
                val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/coding/v1/models", request.path)
                assertEquals("Bearer kimi-token", request.firstHeader("Authorization"))
            } finally {
                proxy.server.stop()
            }
        }
    }

    @Test
    fun augmentsManagedDefaultModelFromModelsDevKimiCodeCatalog() {
        TestUpstream(modelsBody = managedModelsBody("kimi-for-coding")).use { upstream ->
            TestModelsDevCatalog().use { catalog ->
                val proxy = newProxy(upstream, modelsDevCatalogUri = catalog.uri)
                try {
                    proxy.server.start()
                    val response = get(proxy.port, "/v1/models")

                    assertEquals(200, response.statusCode())
                    val ids = JsonHelper.JSON.parseToJsonElement(response.body()).jsonObject["data"]!!.jsonArray
                        .map { it.jsonObject["id"]!!.jsonPrimitive.content }
                    assertEquals(listOf("ki-k2p7", "ki-kimi-k2-thinking", "ki-kimi-for-coding"), ids)
                    assertEquals("/coding/v1/models", assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS)).path)
                    assertEquals("/api.json", assertNotNull(catalog.requests.poll(2, TimeUnit.SECONDS)).path)

                    val models = proxy.provider.models()
                    assertTrue(models.single { it.localId == "ki-kimi-for-coding" }.isDefault)
                    assertTrue(!models.single { it.localId == "ki-k2p7" }.isDefault)

                    val chat = post(
                        proxy.port,
                        "/v1/chat/completions",
                        "{\"model\":\"ki-k2p7\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":16}",
                    )

                    assertEquals(200, chat.statusCode())
                    val chatRequest = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                    assertEquals("/coding/v1/chat/completions", chatRequest.path)
                    assertTrue(chatRequest.body.contains("\"model\":\"k2p7\""), chatRequest.body)
                    assertEquals("KimiCLI/1.40.0", chatRequest.firstHeader("User-Agent"))
                    assertEquals("kimi_cli", chatRequest.firstHeader("X-Msh-Platform"))
                } finally {
                    proxy.server.stop()
                }
            }
        }
    }

    @Test
    fun fallsBackToKimiForCodingWhenDiscoveryFails() {
        TestUpstream(modelsStatus = 500).use { upstream ->
            val proxy = newProxy(upstream)
            try {
                proxy.server.start()
                val response = get(proxy.port, "/v1/models")

                assertEquals(200, response.statusCode())
                val ids = JsonHelper.JSON.parseToJsonElement(response.body()).jsonObject["data"]!!.jsonArray
                    .map { it.jsonObject["id"]!!.jsonPrimitive.content }
                assertEquals(listOf(KimiSubscriptionProxyProvider.PREFIX + KimiSubscriptionProxyProvider.MODEL_ID), ids)
                assertEquals("/coding/v1/models", assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS)).path)
            } finally {
                proxy.server.stop()
            }
        }
    }

    @Test
    fun forwardsAnthropicMessagesToKimiCodingBase() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream)
            try {
                proxy.server.start()
                val response = post(
                    proxy.port,
                    "/v1/messages",
                    "{\"model\":\"ki-kimi-for-coding\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":16}",
                )

                assertEquals(200, response.statusCode())
                assertEquals("/coding/v1/models", assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS)).path)
                val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/coding/v1/messages", request.path)
                assertEquals("Bearer kimi-token", request.firstHeader("Authorization"))
                assertTrue(request.body.contains("\"model\":\"kimi-for-coding\""), request.body)
            } finally {
                proxy.server.stop()
            }
        }
    }

    @Test
    fun forwardsPrefixedAnthropicModelsMissingFromDiscovery() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream)
            try {
                proxy.server.start()
                val response = post(
                    proxy.port,
                    "/v1/messages",
                    "{\"model\":\"ki-k2p6\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":16}",
                )

                assertEquals(200, response.statusCode())
                assertEquals("/coding/v1/models", assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS)).path)
                val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/coding/v1/messages", request.path)
                assertTrue(request.body.contains("\"model\":\"k2p6\""), request.body)
                assertTrue(!request.body.contains("ki-k2p6"), request.body)
            } finally {
                proxy.server.stop()
            }
        }
    }

    private fun newProxy(upstream: TestUpstream, modelsDevCatalogUri: URI? = null): TestProxy {
        val port = freePort()
        val provider = KimiSubscriptionProxyProvider(
            credentialsProvider = { KimiCredentials(accessToken = "kimi-token") },
            openAiCompatibleBaseUri = upstream.openAiBaseUri,
            anthropicCompatibleBaseUri = upstream.anthropicBaseUri,
            modelsDevCatalogUri = modelsDevCatalogUri,
            requestLogDir = Files.createTempDirectory("kimi-subscription-proxy-test-logs").toString(),
        )
        return TestProxy(
            port,
            provider,
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

    private data class TestProxy(
        val port: Int,
        val provider: KimiSubscriptionProxyProvider,
        val server: SubscriptionProxyServer,
    )

    private class TestUpstream(
        private val modelsStatus: Int = 200,
        private val modelsBody: String = managedModelsBody("kimi-for-coding", "kimi-k2.5"),
    ) : AutoCloseable {
        val requests = LinkedBlockingQueue<CapturedRequest>()
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val openAiBaseUri: URI
        val anthropicBaseUri: URI

        init {
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                requests += CapturedRequest(
                    path = exchange.requestURI.rawPath,
                    headers = exchange.requestHeaders.mapValues { it.value.toList() },
                    body = body,
                )
                val isModelsRequest = exchange.requestURI.rawPath.endsWith("/models")
                val responseBody = if (isModelsRequest) {
                    modelsBody
                } else {
                    "{\"id\":\"msg_1\",\"content\":[]}"
                }
                val response = responseBody.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(if (isModelsRequest) modelsStatus else 200, response.size.toLong())
                exchange.responseBody.use { output -> output.write(response) }
            }
            server.start()
            val base = "http://127.0.0.1:${server.address.port}"
            openAiBaseUri = URI.create("$base/coding/v1")
            anthropicBaseUri = URI.create("$base/coding")
        }

        override fun close() {
            server.stop(0)
        }
    }

    private class TestModelsDevCatalog : AutoCloseable {
        val requests = LinkedBlockingQueue<CapturedRequest>()
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val uri: URI

        init {
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                requests += CapturedRequest(
                    path = exchange.requestURI.rawPath,
                    headers = exchange.requestHeaders.mapValues { it.value.toList() },
                    body = body,
                )
                val responseBody = """
                {
                  "kimi-for-coding": {
                    "id": "kimi-for-coding",
                    "name": "Kimi For Coding",
                    "api": "https://api.kimi.com/coding/v1",
                    "models": {
                      "k2p7": {
                        "id": "k2p7",
                        "name": "Kimi K2.7 Code",
                        "attachment": true,
                        "tool_call": true,
                        "limit": { "context": 262144, "output": 32768 }
                      },
                      "kimi-k2-thinking": {
                        "id": "kimi-k2-thinking",
                        "name": "Kimi K2 Thinking",
                        "attachment": false,
                        "tool_call": true,
                        "limit": { "context": 262144, "output": 32768 }
                      },
                      "missing-context": {
                        "id": "missing-context"
                      }
                    }
                  },
                  "moonshotai": {
                    "id": "moonshotai",
                    "api": "https://api.moonshot.ai/v1",
                    "models": {
                      "kimi-api-key-only": {
                        "id": "kimi-api-key-only",
                        "limit": { "context": 131072, "output": 8192 }
                      }
                    }
                  }
                }
                """.trimIndent()
                val response = responseBody.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { output -> output.write(response) }
            }
            server.start()
            uri = URI.create("http://127.0.0.1:${server.address.port}/api.json")
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

        private fun managedModelsBody(vararg ids: String): String {
            val models = ids.joinToString(",\n") { id ->
                val contextLength = if (id == "kimi-k2.5") 250_000 else 262_144
                """
                {
                  "id": "$id",
                  "context_length": $contextLength,
                  "supports_reasoning": true,
                  "supports_image_in": true,
                  "supports_video_in": true,
                  "supports_tool_use": true
                }
                """.trimIndent()
            }
            return """
            {
              "data": [
                $models,
                {
                  "id": "missing-context"
                },
                {
                  "id": "zero-context",
                  "context_length": 0
                }
              ]
            }
            """.trimIndent()
        }
    }
}
