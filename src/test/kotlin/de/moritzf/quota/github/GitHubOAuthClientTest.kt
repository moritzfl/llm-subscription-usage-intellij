package de.moritzf.quota.github

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class GitHubOAuthClientTest {
    @Test
    fun requestDeviceAuthorizationUsesCopilotCliClientId() {
        TestOAuthServer(
            """
            {"device_code":"device-123","user_code":"ABCD-1234","verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}
            """.trimIndent(),
        ).use { server ->
            val client = GitHubOAuthClient(
                deviceCodeEndpoint = server.uri("/login/device/code"),
                accessTokenEndpoint = server.uri("/login/oauth/access_token"),
            )

            val authorization = client.requestDeviceAuthorization()

            assertEquals("device-123", authorization.deviceCode)
            assertEquals("ABCD-1234", authorization.userCode)
            val request = assertNotNull(server.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("/login/device/code", request.path)
            assertEquals(GitHubOAuthClient.CLIENT_ID, request.form["client_id"])
            assertEquals("read:user", request.form["scope"])
            assertEquals("openai-usage-quota-intellij", request.headers["User-agent"])
        }
    }

    @Test
    fun pollDeviceTokenUsesCopilotCliClientIdAndStoresItWithCredentials() {
        TestOAuthServer("""{"access_token":"github-token"}""").use { server ->
            val client = GitHubOAuthClient(
                deviceCodeEndpoint = server.uri("/login/device/code"),
                accessTokenEndpoint = server.uri("/login/oauth/access_token"),
            )

            val result = client.pollDeviceToken("device-123")

            val authorized = assertIs<GitHubDeviceTokenPollResult.Authorized>(result)
            assertEquals("github-token", authorized.credentials.accessToken)
            assertEquals(GitHubOAuthClient.CLIENT_ID, authorized.credentials.oauthClientId)
            val request = assertNotNull(server.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("/login/oauth/access_token", request.path)
            assertEquals(GitHubOAuthClient.CLIENT_ID, request.form["client_id"])
            assertEquals("device-123", request.form["device_code"])
            assertEquals("urn:ietf:params:oauth:grant-type:device_code", request.form["grant_type"])
        }
    }

    private class TestOAuthServer(private val responseBody: String) : AutoCloseable {
        val requests = LinkedBlockingQueue<RecordedRequest>()
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

        init {
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.use { input -> String(input.readAllBytes(), StandardCharsets.UTF_8) }
                requests.add(
                    RecordedRequest(
                        path = exchange.requestURI.path,
                        headers = exchange.requestHeaders.entries.associate { it.key to it.value.joinToString(",") },
                        form = parseForm(body),
                    ),
                )
                val bytes = responseBody.toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        fun uri(path: String): URI = URI.create("http://127.0.0.1:${server.address.port}$path")

        override fun close() {
            server.stop(0)
        }

        private fun parseForm(body: String): Map<String, String> {
            if (body.isBlank()) return emptyMap()
            return body.split('&').associate { pair ->
                val parts = pair.split('=', limit = 2)
                val key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8)
                val value = URLDecoder.decode(parts.getOrElse(1) { "" }, StandardCharsets.UTF_8)
                key to value
            }
        }
    }

    private data class RecordedRequest(
        val path: String,
        val headers: Map<String, String>,
        val form: Map<String, String>,
    )
}
