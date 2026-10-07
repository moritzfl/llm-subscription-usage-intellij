package de.moritzf.quota.idea.mcp

import com.sun.net.httpserver.HttpServer
import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.RealtimeSpeechSession
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

class CodexRealtimeSpeechTest {
    @Test
    fun experimentalCallUsesOwnAuthRefreshAndDoesNotWriteDeniedAudio(@TempDir dir: Path) {
        val requests = LinkedBlockingQueue<Pair<String, String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/backend-api/codex/realtime/calls") { exchange ->
            val auth = exchange.requestHeaders.getFirst("Authorization")
            requests += auth to exchange.requestBody.readAllBytes().decodeToString()
            assertEquals("intent=quicksilver&architecture=avas", exchange.requestURI.rawQuery)
            assertEquals("quicksilver=v2", exchange.requestHeaders.getFirst("openai-alpha"))
            assertNotNull(exchange.requestHeaders.getFirst("session-id"))
            assertNotNull(exchange.requestHeaders.getFirst("thread-id"))
            val body = "private provider failure".toByteArray()
            exchange.sendResponseHeaders(if (auth == "Bearer stale") 401 else 403, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            HttpClient.newHttpClient().use { http ->
                var token = "stale"
                val client = CodexMcpClient({ token }, { "account" }, { stale -> assertEquals("stale", stale); token = "fresh"; token }, http,
                    URI.create("http://127.0.0.1:${server.address.port}/backend-api/codex"))
                val result = client.synthesize("hello", "test.wav", dir, "marin", RealtimeSpeechSession.MODEL, "wav")
                assertTrue(result.isError); assertTrue(result.body.contains("HTTP 403")); assertFalse(result.body.contains("private provider"))
                assertFalse(Files.exists(dir.resolve("test.wav")))
                val first = assertNotNull(requests.poll(2, TimeUnit.SECONDS)); val retry = assertNotNull(requests.poll(2, TimeUnit.SECONDS))
                assertEquals("Bearer stale", first.first); assertEquals("Bearer fresh", retry.first); assertEquals(first.second, retry.second)
                val body = JsonSupport.json.parseToJsonElement(first.second).jsonObject
                assertTrue(body["sdp"]!!.jsonPrimitive.content.startsWith("v=0"))
                assertEquals(RealtimeSpeechSession.MODEL, body["session"]!!.jsonObject["model"]!!.jsonPrimitive.content)
            }
        } finally { server.stop(0) }
    }
    @Test
    fun experimentalFormatMustBeExplicitAndCannotMislabelWav(@TempDir dir: Path) {
        val client = CodexMcpClient({ error("No auth required for validation") }, { null })
        val response = client.synthesize("hello", "test.mp3", dir, model = RealtimeSpeechSession.MODEL, responseFormat = "wav")
        assertTrue(response.isError); assertTrue(response.body.contains(".wav")); assertFalse(Files.exists(dir.resolve("test.mp3")))
    }
}
