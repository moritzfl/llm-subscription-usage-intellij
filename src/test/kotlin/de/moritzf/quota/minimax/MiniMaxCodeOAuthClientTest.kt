package de.moritzf.quota.minimax

import com.sun.net.httpserver.HttpServer
import de.moritzf.quota.shared.auth.OAuthCredentials
import de.moritzf.quota.shared.auth.OAuthUrlCodec
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MiniMaxCodeOAuthClientTest {
    @Test
    fun deviceLoginSendsPkceAndPollsWithTheDeviceCode() {
        AccountServer(
                "/oauth2/device/code" to
                    (200 to
                        """{"device_code":"dev-1","user_code":"DBH3-5WL3","verification_uri":"https://account.minimax.io/oauth-authorize","verification_uri_complete":"https://account.minimax.io/oauth-authorize?user_code=DBH3-5WL3","expires_in":300,"interval":3}"""),
                "/oauth2/token" to (400 to """{"error":"authorization_pending"}"""),
                "/oauth2/token" to (200 to """{"status":"slow_down"}"""),
                "/oauth2/token" to
                    (200 to
                        """{"access_token":"at-1","refresh_token":"rt-1","token_type":"Bearer","expires_in":43200,"scope":"agent.default"}"""),
            )
            .use { server ->
                val client = server.client()
                val authorization = client.requestDeviceAuthorization(MiniMaxRegion.GLOBAL)

                assertEquals("DBH3-5WL3", authorization.userCode)
                assertEquals(
                    "https://account.minimax.io/oauth-authorize?user_code=DBH3-5WL3",
                    authorization.verificationUrl,
                )
                assertEquals(300, authorization.expiresInSeconds)
                assertEquals(3, authorization.intervalSeconds)
                val start = server.form()
                assertEquals("mcode-public", start["client_id"])
                assertEquals("agent.default", start["scope"])
                assertEquals("agent-backend", start["audience"])
                assertEquals("S256", start["code_challenge_method"])

                assertEquals(MiniMaxCodeTokenResult.Pending, client.pollDeviceToken(authorization))
                assertEquals(MiniMaxCodeTokenResult.SlowDown, client.pollDeviceToken(authorization))
                val authorized =
                    assertIs<MiniMaxCodeTokenResult.Authorized>(
                        client.pollDeviceToken(authorization),
                    )

                val poll = server.form()
                assertEquals("urn:ietf:params:oauth:grant-type:device_code", poll["grant_type"])
                assertEquals("dev-1", poll["device_code"])
                assertEquals(
                    MiniMaxCodeOAuthClient.codeChallenge(poll.getValue("code_verifier")),
                    start["code_challenge"],
                )
                with(authorized.credentials) {
                    assertEquals("at-1", accessToken)
                    assertEquals("rt-1", refreshToken)
                    assertEquals(NOW + 43_200_000, expiresAt)
                    assertEquals(server.uri.toString(), issuer)
                }
            }
    }

    @Test
    fun accountServerStyleCodesPollWithTheUserCode() {
        AccountServer(
                "/oauth2/device/code" to
                    (200 to
                        """{"user_code":"ABCD","verification_url":"https://account.minimax.io/x","expired_in":${NOW + 120_000},"interval":5000}"""),
                "/oauth2/token" to (200 to """{"status":"pending"}"""),
            )
            .use { server ->
                val client = server.client()
                val authorization = client.requestDeviceAuthorization(MiniMaxRegion.GLOBAL)

                assertEquals(120, authorization.expiresInSeconds)
                assertEquals(5, authorization.intervalSeconds)
                assertEquals(MiniMaxCodeTokenResult.Pending, client.pollDeviceToken(authorization))
                server.form()
                val poll = server.form()
                assertEquals("ABCD", poll["user_code"])
                assertNull(poll["device_code"])
            }
    }

    @Test
    fun anExpiredOrDeniedLoginIsReported() {
        for ((reply, code) in
            listOf(
                (400 to """{"error":"expired_token"}""") to "expired_token",
                (200 to """{"status":"denied"}""") to "access_denied",
            )) {
            AccountServer(DEVICE_CODE, "/oauth2/token" to reply).use { server ->
                val client = server.client()
                val authorization = client.requestDeviceAuthorization(MiniMaxRegion.GLOBAL)
                val exception =
                    assertFailsWith<MiniMaxCodeAuthException> {
                        client.pollDeviceToken(authorization)
                    }
                assertEquals(code, exception.oauthError)
            }
        }
    }

    @Test
    fun refreshRotatesTheRefreshTokenAndKeepsTheAccount() {
        AccountServer(
                "/oauth2/token" to
                    (200 to
                        """{"access_token":"at-2","refresh_token":"rt-2","token_type":"Bearer","expires_in":43200,"scope":"agent.default"}"""),
            )
            .use { server ->
                val refreshed =
                    server
                        .client()
                        .refresh(
                            OAuthCredentials(
                                accessToken = "at-1",
                                refreshToken = "rt-1",
                                accountId = "575100920747536392",
                                issuer = server.uri.toString(),
                            ),
                        )

                val form = server.form()
                assertEquals("refresh_token", form["grant_type"])
                assertEquals("rt-1", form["refresh_token"])
                assertEquals("mcode-public", form["client_id"])
                assertEquals("at-2", refreshed.accessToken)
                assertEquals("rt-2", refreshed.refreshToken)
                assertEquals("575100920747536392", refreshed.accountId)
                assertEquals(server.uri.toString(), refreshed.issuer)
            }
    }

    @Test
    fun aUsedRefreshTokenIsATerminalInvalidGrant() {
        // Live: reusing a rotated refresh token answers HTTP 400 invalid_grant.
        AccountServer("/oauth2/token" to (400 to """{"error":"invalid_grant"}""")).use { server ->
            val exception =
                assertFailsWith<MiniMaxCodeAuthException> {
                    server
                        .client()
                        .refresh(OAuthCredentials(accessToken = "at", refreshToken = "used"))
                }
            assertEquals(400, exception.statusCode)
            assertEquals("invalid_grant", exception.oauthError)
            assertTrue("used" !in exception.message.orEmpty())
        }
    }

    @Test
    fun theIssuerNamesTheRegionOfALogin() {
        assertEquals(
            MiniMaxRegion.GLOBAL,
            MiniMaxCodeHosts.regionOfIssuer("https://account.minimax.io"),
        )
        assertEquals(
            MiniMaxRegion.CN,
            MiniMaxCodeHosts.regionOfIssuer("https://account.minimax.cn/"),
        )
        assertNull(MiniMaxCodeHosts.regionOfIssuer("https://evil.example"))
        assertEquals(
            URI.create("https://agent.minimaxi.com"),
            MiniMaxCodeHosts.forRegion(MiniMaxRegion.CN).agent,
        )
    }

    private class AccountServer(vararg replies: Pair<String, Pair<Int, String>>) : AutoCloseable {
        private val replies = ConcurrentLinkedQueue(replies.toList())
        private val forms = LinkedBlockingQueue<Map<String, String>>()
        private val server =
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
                createContext("/") { exchange ->
                    val body = exchange.requestBody.use { it.readBytes().decodeToString() }
                    forms += OAuthUrlCodec.parseQuery(body)
                    val path = exchange.requestURI.rawPath
                    val reply =
                        this@AccountServer.replies
                            .firstOrNull { it.first == path }
                            ?.also { this@AccountServer.replies.remove(it) }
                    val (status, text) = reply?.second ?: (404 to "{}")
                    val bytes = text.toByteArray()
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                start()
            }
        val uri: URI = URI.create("http://127.0.0.1:${server.address.port}")

        fun client() =
            MiniMaxCodeOAuthClient(nowMs = { NOW }, hosts = { MiniMaxCodeHosts(uri, uri, uri) })

        fun form(): Map<String, String> = forms.remove()

        override fun close() = server.stop(0)
    }

    private companion object {
        const val NOW = 1_794_037_149_123L
        val DEVICE_CODE =
            "/oauth2/device/code" to
                (200 to
                    """{"device_code":"d","user_code":"U","verification_uri_complete":"https://account.minimax.io/a?user_code=U","expires_in":300,"interval":1}""")
    }
}
