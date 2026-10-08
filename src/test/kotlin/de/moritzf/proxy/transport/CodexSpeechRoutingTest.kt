package de.moritzf.proxy.transport

import com.sun.net.httpserver.HttpServer
import de.moritzf.proxy.auth.CredentialsProvider
import de.moritzf.proxy.config.ServerConfig
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CodexSpeechRoutingTest {
    @Test
    fun dictationUsesChatGptRouteAndReplaysMultipartWithRefreshedCredentials() {
        val requests = LinkedBlockingQueue<List<String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val auth = exchange.requestHeaders.getFirst("Authorization")
            requests +=
                listOf(
                    exchange.requestURI.toString(),
                    auth,
                    exchange.requestBody.readAllBytes().decodeToString(),
                )
            val response = """{"text":"Hello"}""".toByteArray()
            exchange.sendResponseHeaders(
                if (auth == "Bearer old") 401 else 200,
                response.size.toLong(),
            )
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            var token = "old"
            var refreshes = 0
            val credentials =
                object : CredentialsProvider {
                    override fun getAuthHeaders() = mapOf("Authorization" to "Bearer $token")

                    override fun refreshAfterUnauthorized(
                        rejectedAuthorizationHeader: String?
                    ): Boolean {
                        assertEquals("Bearer old", rejectedAuthorizationHeader)
                        refreshes++
                        token = "new"
                        return true
                    }
                }
            val origin = "http://127.0.0.1:${server.address.port}"
            val config =
                ServerConfig(
                    "127.0.0.1",
                    1,
                    null,
                    null,
                    "$origin/backend-api/codex/",
                    null,
                    null,
                    null,
                    "",
                    false,
                    emptyMap(),
                    null,
                )
            val client = CodexHttpClient(config, HttpClient.newHttpClient(), credentials)
            val body =
                "--audio\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\ngpt-transcribe\r\n--audio--\r\n"
            val response =
                client.requestBytes(
                    "/v1/audio/transcriptions?language=en",
                    "POST",
                    mapOf("Content-Type" to "multipart/form-data; boundary=audio"),
                    HttpRequest.BodyPublishers.ofString(body),
                )
            assertEquals(200, response.statusCode())
            assertEquals(1, refreshes)
            assertEquals(
                listOf("/backend-api/transcribe?language=en", "Bearer old", body),
                requests.poll(2, TimeUnit.SECONDS),
            )
            assertEquals(
                listOf("/backend-api/transcribe?language=en", "Bearer new", body),
                requests.poll(2, TimeUnit.SECONDS),
            )

            for ((base, route, expected) in
                listOf(
                    Triple(
                        "/backend-api/codex",
                        "/audio/speech",
                        "/backend-api/codex/audio/speech",
                    ),
                    Triple("/backend-api/codex", "/responses", "/backend-api/codex/responses"),
                    Triple("/v1", "/audio/transcriptions", "/v1/audio/transcriptions"),
                )) {
                val other =
                    ServerConfig(
                        "127.0.0.1",
                        1,
                        null,
                        null,
                        origin + base,
                        null,
                        null,
                        null,
                        "",
                        false,
                        emptyMap(),
                        null,
                    )
                CodexHttpClient(other, HttpClient.newHttpClient(), credentials)
                    .requestBytes(route, "POST", byteArrayOf(), emptyMap())
                assertEquals(expected, assertNotNull(requests.poll(2, TimeUnit.SECONDS)).first())
            }
        } finally {
            server.stop(0)
        }
    }
}
