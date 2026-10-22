package de.moritzf.quota.idea.openai

import com.sun.net.httpserver.HttpServer
import de.moritzf.quota.idea.mcp.CodexMcpClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class IdeMediaOperationsTest {
    @Test
    fun proxySpeechWritesThroughTheProviderClientAndReturnsTheAudio() {
        val audio = byteArrayOf(9, 8, 7, 6)
        val paths = LinkedBlockingQueue<String>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            paths += exchange.requestURI.rawPath
            exchange.responseHeaders.set("Content-Type", "audio/mpeg")
            exchange.sendResponseHeaders(200, audio.size.toLong())
            exchange.responseBody.use { it.write(audio) }
        }
        server.start()
        try {
            val codex =
                CodexMcpClient(
                    accessTokenProvider = { "codex-token" },
                    accountIdProvider = { "account-1" },
                    httpClient = HttpClient.newHttpClient(),
                    upstreamBaseUri =
                        URI.create("http://127.0.0.1:${server.address.port}/backend-api/codex"),
                )
            val operations = IdeMediaOperations(codex = { codex })

            val speech =
                operations.synthesizeSpeech("openai", "gpt-4o-mini-tts", "Hello", null, "mp3")

            assertEquals(audio.toList(), speech.bytes.toList())
            assertEquals("/backend-api/codex/audio/speech", paths.poll(2, TimeUnit.SECONDS))
        } finally {
            server.stop(0)
        }
    }
}
