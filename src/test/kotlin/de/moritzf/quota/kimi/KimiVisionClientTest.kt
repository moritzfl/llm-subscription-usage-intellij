package de.moritzf.quota.kimi

import com.sun.net.httpserver.HttpServer
import de.moritzf.quota.shared.JsonSupport
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class KimiVisionClientTest {
    @Test
    fun postsImageQuestionAndReturnsAnswerWithCredentials() {
        TestUpstream("""{"choices":[{"message":{"content":"A sunset."}}]}""").use { upstream ->
            val client = KimiVisionClient(chatEndpoint = upstream.baseUri)
            val credentials = KimiCredentials(
                accessToken = "kimi-token",
                refreshToken = "kimi-refresh",
                expiresAtEpochSeconds = (System.currentTimeMillis() / 1000.0) + 3600,
            )

            val result = client.ask(
                credentials,
                imageUrl = "https://example.com/a.png",
                prompt = "Describe",
                model = "kimi-vision",
            )

            assertEquals("A sunset.", result.answer)
            assertEquals(credentials, result.credentials)
            val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("/coding/v1/chat/completions", request.path)
            assertTrue(request.authorization.contains("Bearer kimi-token"))
            assertTrue(request.userAgent.contains("KimiCLI"))
            val body = JsonSupport.json.parseToJsonElement(request.body) as JsonObject
            assertEquals("kimi-vision", body["model"]!!.jsonPrimitive.content)
            val content = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
            assertEquals("image_url", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        }
    }

    private class TestUpstream(
        private val responseBody: String,
    ) : AutoCloseable {
        val requests = LinkedBlockingQueue<CapturedRequest>()
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val baseUri: java.net.URI

        init {
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                requests += CapturedRequest(
                    exchange.requestURI.rawPath,
                    body,
                    exchange.requestHeaders.getFirst("Authorization").orEmpty(),
                    exchange.requestHeaders.getFirst("User-Agent").orEmpty(),
                )
                val payload = responseBody.toByteArray()
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
            server.start()
            baseUri = java.net.URI.create("http://127.0.0.1:${server.address.port}/coding/v1/chat/completions")
        }

        override fun close() {
            server.stop(0)
        }
    }

    private data class CapturedRequest(
        val path: String,
        val body: String,
        val authorization: String,
        val userAgent: String,
    )
}
