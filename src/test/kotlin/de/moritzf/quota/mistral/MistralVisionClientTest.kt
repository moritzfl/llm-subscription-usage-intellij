package de.moritzf.quota.mistral

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

class MistralVisionClientTest {
    @Test
    fun postsImageQuestionToChatCompletionsAndReturnsAnswer() {
        TestUpstream("""{"choices":[{"message":{"content":"A red square."}}]}""").use { upstream ->
            val client = MistralVisionClient(chatCompletionsUri = upstream.baseUri)

            val answer = client.ask(
                "mistral-key",
                imageUrl = "https://example.com/a.png",
                prompt = "What is shown?",
                model = "pixtral-large-latest",
            )

            assertEquals("A red square.", answer)
            val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("/v1/chat/completions", request.path)
            assertTrue(request.authorization.contains("Bearer mistral-key"))
            val body = JsonSupport.json.parseToJsonElement(request.body) as JsonObject
            assertEquals("pixtral-large-latest", body["model"]!!.jsonPrimitive.content)
            val content = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
            assertEquals("image_url", content[0].jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals(
                "https://example.com/a.png",
                content[0].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
            )
            assertEquals("What is shown?", content[1].jsonObject["text"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun rejectsBadApiKeyWithTypedException() {
        TestUpstream("""{"error":"bad key"}""", status = 401).use { upstream ->
            val client = MistralVisionClient(chatCompletionsUri = upstream.baseUri)
            val exception = assertFailsWith<MistralQuotaException> {
                client.ask("bad", imageUrl = "https://example.com/a.png", prompt = "?", model = "pixtral-large-latest")
            }
            assertTrue(exception.message!!.contains("API key"))
        }
    }

    @Test
    fun missingImageFailsBeforeRequest() {
        val client = MistralVisionClient(chatCompletionsUri = URI.create("http://127.0.0.1:1/v1/chat/completions"))
        val exception = assertFailsWith<MistralQuotaException> {
            client.ask("key", prompt = "?", model = "pixtral-large-latest")
        }
        assertTrue(exception.message!!.contains("image"))
    }

    private class TestUpstream(
        private val responseBody: String,
        private val status: Int = 200,
    ) : AutoCloseable {
        val requests = LinkedBlockingQueue<CapturedRequest>()
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val baseUri: URI

        init {
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                requests += CapturedRequest(
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

    private data class CapturedRequest(val path: String, val body: String, val authorization: String)
}
