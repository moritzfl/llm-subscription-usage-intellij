package de.moritzf.quota.ollama

import com.sun.net.httpserver.HttpServer
import de.moritzf.quota.shared.JsonSupport
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OllamaVisionClientTest {
    @Test
    fun postsImageQuestionAndReturnsAnswer() {
        TestUpstream("""{"choices":[{"message":{"content":"A robot."}}]}""").use { upstream ->
            val client = OllamaVisionClient(chatCompletionsUri = upstream.baseUri)

            val answer =
                client.ask(
                    "ollama-key",
                    imageUrl = "https://example.com/a.png",
                    prompt = "Describe",
                    model = "qwen2.5vl",
                )

            assertEquals("A robot.", answer)
            val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("/v1/chat/completions", request.path)
            assertTrue(request.authorization.contains("Bearer ollama-key"))
            val body = JsonSupport.json.parseToJsonElement(request.body) as JsonObject
            assertEquals("qwen2.5vl", body["model"]!!.jsonPrimitive.content)
            val content = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
            assertEquals("image_url", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun rejectsBadApiKeyWithTypedException() {
        TestUpstream("""{"error":"bad key"}""", status = 401).use { upstream ->
            val client = OllamaVisionClient(chatCompletionsUri = upstream.baseUri)
            val exception =
                assertFailsWith<OllamaQuotaException> {
                    client.ask(
                        "bad",
                        imageUrl = "https://example.com/a.png",
                        prompt = "?",
                        model = "qwen2.5vl",
                    )
                }
            assertTrue(exception.message!!.contains("API key"))
        }
    }

    private class TestUpstream(
        private val responseBody: String,
        private val status: Int = 200,
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
                        exchange.requestURI.rawPath,
                        body,
                        exchange.requestHeaders.getFirst("Authorization").orEmpty(),
                    )
                val payload = responseBody.toByteArray()
                exchange.sendResponseHeaders(status, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
            server.start()
            baseUri = URI.create("http://127.0.0.1:${server.address.port}/v1/chat/completions")
        }

        override fun close() {
            server.stop(0)
        }
    }

    private data class CapturedRequest(
        val path: String,
        val body: String,
        val authorization: String,
    )
}
