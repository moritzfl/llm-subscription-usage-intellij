package de.moritzf.quota.minimax

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import de.moritzf.quota.shared.JsonSupport

class MiniMaxAudioClientTest {
    @Test
    fun hexToBytesDecodesAudioPayload() {
        assertEquals(listOf(0x0A, 0xFF), MiniMaxAudioClient.hexToBytes("0aff").map { it.toInt() and 0xFF })
        assertEquals(
            "abcd",
            MiniMaxAudioClient.audioHex("""{"data":{"audio":"abcd","status":2}}"""),
        )
    }

    @Test
    fun transcribePostsMultipartAsrRequest() {
        TestMiniMaxAudioServer(
            responseBody = """{"text":"hello","duration":1.5,"trace_id":"t1"}""",
        ).use { server ->
            val client = MiniMaxAudioClient(
                httpClient = HttpClient.newHttpClient(),
                globalApiHost = server.baseUri,
            )
            val audio = Files.createTempFile("minimax-stt-", ".mp3")
            Files.write(audio, byteArrayOf(1, 2, 3, 4))
            try {
                val result = client.transcribe("sk-test", MiniMaxRegion.GLOBAL, audio, language = "en", diarize = true)
                val json = JsonSupport.json.parseToJsonElement(result).jsonObject
                assertEquals("hello", json["text"]!!.jsonPrimitive.content)
                val request = assertNotNull(server.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/v1/speech_to_text", request.path)
                assertEquals("en", request.firstHeader("language"))
                assertTrue(request.body.contains("asr-1.0"))
                assertTrue(request.body.contains("verbose_json"))
            } finally {
                Files.deleteIfExists(audio)
            }
        }
    }

    @Test
    fun transcribeRequiresLocalFile() {
        val client = MiniMaxAudioClient()
        val exception = assertFailsWith<MiniMaxQuotaException> {
            client.transcribe("sk-test", MiniMaxRegion.GLOBAL, localFile = null)
        }
        assertEquals("MiniMax speech-to-text requires a local audio file.", exception.message)
    }

    private class TestMiniMaxAudioServer(
        private val responseBody: String,
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
                val response = responseBody.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { output -> output.write(response) }
            }
            server.start()
            baseUri = URI.create("http://127.0.0.1:${server.address.port}/")
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
            return headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
        }
    }
}
