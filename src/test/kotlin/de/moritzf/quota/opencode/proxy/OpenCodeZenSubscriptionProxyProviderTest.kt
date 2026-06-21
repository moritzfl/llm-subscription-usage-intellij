package de.moritzf.quota.opencode.proxy

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

class OpenCodeZenSubscriptionProxyProviderTest {
    @Test
    fun advertisesOpenCodeModelsWithOcPrefixAndRewritesUpstreamModel() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.server.start()

                val modelsResponse = get(proxy.port, "/v1/models")
                assertEquals(200, modelsResponse.statusCode())
                val ids = JsonHelper.JSON.parseToJsonElement(modelsResponse.body()).jsonObject["data"]!!.jsonArray
                    .map { it.jsonObject["id"]!!.jsonPrimitive.content }
                assertEquals(listOf("oc-qwen3-coder"), ids)
                assertEquals("/v1/models", assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS)).path)

                val chatResponse = post(
                    proxy.port,
                    "/v1/chat/completions",
                    "{\"model\":\"oc-qwen3-coder\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                )

                assertEquals(200, chatResponse.statusCode())
                val chatRequest = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/v1/chat/completions", chatRequest.path)
                assertEquals("Bearer opencode-key", chatRequest.firstHeader("Authorization"))
                assertTrue(chatRequest.body.contains("\"model\":\"qwen3-coder\""), chatRequest.body)
            } finally {
                proxy.server.stop()
            }
        }
    }

    private fun newProxy(upstreamBaseUri: URI): TestProxy {
        val port = freePort()
        val provider = OpenCodeZenSubscriptionProxyProvider(
            apiKeyProvider = { "opencode-key" },
            upstreamBaseUri = upstreamBaseUri,
            requestLogDir = Files.createTempDirectory("opencode-subscription-proxy-test-logs").toString(),
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
                        "{\"id\":\"qwen3-coder\",\"object\":\"model\"}," +
                        "{\"id\":\"text-embedding-3-small\",\"object\":\"embedding\"}" +
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
