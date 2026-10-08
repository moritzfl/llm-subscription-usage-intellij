package de.moritzf.quota.supergrok

import com.sun.net.httpserver.HttpServer
import de.moritzf.quota.shared.JsonSupport
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SuperGrokAudioClientTest {
    @Test
    fun postsSttWithUrlAndReturnsProviderJson() {
        TestServer(jsonBody = """{"text":"hello grok","duration":1.2}""").use { server ->
            val client = SuperGrokAudioClient(httpClient = httpClient, baseUri = server.baseUri)
            val result =
                client.transcribe(
                    accessToken = "grok-token",
                    audioUrl = "https://example.com/a.mp3",
                )
            assertEquals(
                "hello grok",
                JsonSupport.json
                    .parseToJsonElement(result)
                    .jsonObject["text"]!!
                    .jsonPrimitive
                    .content,
            )
            val request = assertNotNull(server.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("POST", request.method)
            assertEquals("/stt", request.path)
            assertTrue(request.body.contains("https://example.com/a.mp3"))
        }
    }

    @Test
    fun writesTtsBytesAndUsesEveByDefault() {
        val audio = byteArrayOf(1, 2, 3)
        TestServer(binaryBody = audio).use { server ->
            val dir = Files.createTempDirectory("grok-tts")
            val client = SuperGrokAudioClient(httpClient = httpClient, baseUri = server.baseUri)
            val result =
                client.synthesize(accessToken = "grok-token", text = "Hi", baseDirectory = dir)
            val written = JsonSupport.json.parseToJsonElement(result).jsonObject
            val output = java.nio.file.Path.of(written["output_file"]!!.jsonPrimitive.content)
            assertEquals(dir.toAbsolutePath().normalize(), output.parent)
            assertTrue(output.fileName.toString().matches(Regex("speech-[0-9a-f-]{36}\\.mp3")))
            assertEquals(audio.toList(), Files.readAllBytes(output).toList())
            val request = assertNotNull(server.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("/tts", request.path)
            assertTrue(request.body.contains("eve"))
            assertTrue(request.body.contains("Hi"))
        }
    }

    @Test
    fun listsVoicesFromTtsVoices() {
        TestServer(jsonBody = """{"voices":[{"voice_id":"eve","name":"Eve"}]}""").use { server ->
            val client = SuperGrokAudioClient(httpClient = httpClient, baseUri = server.baseUri)
            val result = client.listVoices("grok-token")
            assertTrue(result.contains("eve"))
            val request = assertNotNull(server.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("GET", request.method)
            assertEquals("/tts/voices", request.path)
        }
    }

    private class TestServer(
        private val jsonBody: String = "{}",
        private val binaryBody: ByteArray? = null,
    ) : AutoCloseable {
        val requests = LinkedBlockingQueue<CapturedRequest>()
        private val server =
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val baseUri: java.net.URI

        init {
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                requests +=
                    CapturedRequest(exchange.requestMethod, exchange.requestURI.rawPath, body)
                val response = binaryBody ?: jsonBody.toByteArray()
                exchange.responseHeaders.set(
                    "Content-Type",
                    if (binaryBody != null) "audio/mpeg" else "application/json",
                )
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            server.start()
            baseUri = java.net.URI.create("http://127.0.0.1:${server.address.port}/")
        }

        override fun close() {
            server.stop(0)
        }
    }

    private data class CapturedRequest(val method: String, val path: String, val body: String)

    private companion object {
        val httpClient: HttpClient = HttpClient.newHttpClient()
    }
}
