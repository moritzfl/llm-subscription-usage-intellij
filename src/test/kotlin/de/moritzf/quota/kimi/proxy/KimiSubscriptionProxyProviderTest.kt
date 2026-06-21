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
    fun advertisesKimiForCodingFromOAuthCredentials() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream)
            try {
                proxy.server.start()
                val response = get(proxy.port, "/v1/models")

                assertEquals(200, response.statusCode())
                val ids = JsonHelper.JSON.parseToJsonElement(response.body()).jsonObject["data"]!!.jsonArray
                    .map { it.jsonObject["id"]!!.jsonPrimitive.content }
                assertEquals(listOf(KimiSubscriptionProxyProvider.MODEL_ID), ids)
                assertEquals("Kimi Code", proxy.provider.models().single().providerName)
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
                    "{\"model\":\"kimi-for-coding\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":16}",
                )

                assertEquals(200, response.statusCode())
                val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/coding/v1/messages", request.path)
                assertEquals("Bearer kimi-token", request.firstHeader("Authorization"))
                assertTrue(request.body.contains("\"model\":\"kimi-for-coding\""), request.body)
            } finally {
                proxy.server.stop()
            }
        }
    }

    private fun newProxy(upstream: TestUpstream): TestProxy {
        val port = freePort()
        val provider = KimiSubscriptionProxyProvider(
            credentialsProvider = { KimiCredentials(accessToken = "kimi-token") },
            openAiCompatibleBaseUri = upstream.openAiBaseUri,
            anthropicCompatibleBaseUri = upstream.anthropicBaseUri,
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

    private class TestUpstream : AutoCloseable {
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
                val responseBody = "{\"id\":\"msg_1\",\"content\":[]}"
                val response = responseBody.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong())
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
