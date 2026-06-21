package de.moritzf.proxy.subscription

import com.sun.net.httpserver.HttpServer
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OpenAiCompatibleApiKeySubscriptionProxyProviderTest {
    @Test
    fun discoversOpenAiCompatibleModelsAndForwardsChat() {
        TestUpstream(modelsBody = """
            {"object":"list","data":[
              {"id":"model-a","object":"model"},
              {"id":"embed-a","object":"embedding"}
            ]}
        """.trimIndent()).use { upstream ->
            val proxy = newProxy(
                OpenAiCompatibleApiKeySubscriptionProxyProvider(
                    id = "test-provider",
                    displayName = "Test Provider",
                    litellmProvider = "test-provider",
                    baseUri = upstream.baseUri,
                    apiKeyProvider = { "provider-key" },
                    localIdPrefix = "test-",
                    requestLogDir = Files.createTempDirectory("api-key-provider-test-logs").toString(),
                ),
            )
            try {
                proxy.server.start()

                val modelsResponse = get(proxy.port, "/v1/models")
                val ids = JsonHelper.JSON.parseToJsonElement(modelsResponse.body()).jsonObject["data"]!!.jsonArray
                    .map { it.jsonObject["id"]!!.jsonPrimitive.content }
                assertEquals(listOf("test-model-a"), ids)

                val chatResponse = post(proxy.port, "/v1/chat/completions", "{" +
                    "\"model\":\"test-model-a\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}" )
                assertEquals(200, chatResponse.statusCode())
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                val chatRequest = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/v1/chat/completions", chatRequest.path)
                assertEquals("Bearer provider-key", chatRequest.firstHeader("Authorization"))
                assertTrue(chatRequest.body.contains("\"model\":\"model-a\""), chatRequest.body)
            } finally {
                proxy.server.stop()
            }
        }
    }

    @Test
    fun advertisesStaticModelsWhenDiscoveryIsDisabled() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(
                OpenAiCompatibleApiKeySubscriptionProxyProvider(
                    id = "zai",
                    displayName = "Z.ai",
                    litellmProvider = "zai",
                    baseUri = upstream.baseUri,
                    apiKeyProvider = { "zai-key" },
                    discoverModels = false,
                    staticModels = listOf(
                        OpenAiCompatibleApiKeySubscriptionProxyProvider.StaticModel("glm-5.1"),
                        OpenAiCompatibleApiKeySubscriptionProxyProvider.StaticModel("glm-5.2", isDefault = true),
                    ),
                    requestLogDir = Files.createTempDirectory("static-provider-test-logs").toString(),
                ),
            )
            try {
                proxy.server.start()

                val response = post(proxy.port, "/v1/chat/completions", "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")

                assertEquals(200, response.statusCode())
                val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/v1/chat/completions", request.path)
                assertTrue(request.body.contains("\"model\":\"glm-5.2\""), request.body)
            } finally {
                proxy.server.stop()
            }
        }
    }

    private fun newProxy(provider: SubscriptionProxyProvider): TestProxy {
        val port = freePort()
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

    private class TestUpstream(
        private val modelsBody: String = "{\"object\":\"list\",\"data\":[]}",
    ) : AutoCloseable {
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
