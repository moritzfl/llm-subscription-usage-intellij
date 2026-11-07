package de.moritzf.quota.minimax

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MiniMaxCodeAccountClientTest {
    @Test
    fun attributionHeadersMatchMiniMaxCode() {
        // Expected values computed with MiniMax Code's own TypeScript (matrix-account-client.ts).
        val path =
            "/matrix/api/v1/commerce/get_membership_info?device_platform=mcode&biz_id=3&app_id=3001&version_code=22201&unix=1794037149123&timezone_offset=3600&sys_language=en&lang=en&device_id=0&os_name=darwin&browser_name=mcode&user_id=575100920747536392&client=mcode"
        val cases =
            listOf(
                Triple(
                    null,
                    "23b5040a6d425754617599b6c1e42673",
                    "9baf3c3e8c7af44d90d7d6a70c922378",
                ),
                Triple(
                    "{}",
                    "23b5040a6d425754617599b6c1e42673",
                    "87901e303a2a50c0261c51af973ba59e",
                ),
                Triple(
                    """{"workspace_id":0}""",
                    "88697039cf5659c4cf3ad387ea3e21b8",
                    "d7239ba6cd8d7bc52424ce946df68f76",
                ),
            )
        for ((body, yy, signature) in cases) {
            val headers = MiniMaxCodeAccountClient.attributionHeaders(path, body, NOW)
            assertEquals(yy, headers["yy"], "yy for $body")
            assertEquals("1794037149", headers["x-timestamp"])
            assertEquals(signature, headers["x-signature"], "signature for $body")
        }
    }

    @Test
    fun encodesLikeJavaScript() {
        assertEquals(
            "%2Fa%3Fb%3D1%26c%3D-_.!~*'()%20%C3%A4",
            MiniMaxCodeAccountClient.encodeUriComponent("/a?b=1&c=-_.!~*'() ä"),
        )
        assertEquals("darwin", MiniMaxCodeAccountClient.platformName("Mac OS X"))
        assertEquals("win32", MiniMaxCodeAccountClient.platformName("Windows 11"))
        assertEquals("linux", MiniMaxCodeAccountClient.platformName("Linux"))
    }

    @Test
    fun readsCreditsOfTheAccountWithoutAPlan() {
        AgentServer(
                USER_INFO,
                EXTRA_INFO,
                "/matrix/api/v1/commerce/get_membership_info" to MEMBERSHIP_WITHOUT_PLAN,
            )
            .use { server ->
                val client = server.client()
                assertEquals("575100920747536392", client.fetchUserId("at", MiniMaxRegion.GLOBAL))
                val account =
                    client.fetchAccount(
                        "at",
                        MiniMaxRegion.GLOBAL,
                        "575100920747536392",
                        includeTokenPlan = true,
                    )

                assertEquals(false, account.hasTokenPlan)
                assertEquals(MiniMaxCredits(3944.267, 3144.267, 800.0), account.credits)
                assertNull(account.tokenPlanJson, "no plan, so its windows are not read")
                assertTrue(account.membershipJson.contains("\"op_credit_summary\""))

                val info = server.request()
                assertEquals("GET", info.method)
                assertEquals("Bearer at", info.authorization)
                assertEquals("MiniMaxCode", info.userAgent)
                assertTrue("user_id=0" in info.query)
                server.request() // workspace lookup
                val membership = server.request()
                assertEquals("""{"workspace_id":0}""", membership.body)
                assertTrue("user_id=575100920747536392" in membership.query)
                assertTrue("os_name=darwin" in membership.query)
                // The server can check the signature over exactly what was sent.
                val expected =
                    MiniMaxCodeAccountClient.attributionHeaders(
                        "${membership.path}?${membership.query}",
                        membership.body,
                        NOW,
                    )
                assertEquals(expected["yy"], membership.yy)
                assertEquals(expected["x-signature"], membership.signature)
                assertNull(server.pollRequest())
            }
    }

    @Test
    fun readsThePlanWindowsWithTheLoginWhenThereIsAPlan() {
        AgentServer(
                EXTRA_INFO,
                "/matrix/api/v1/commerce/get_membership_info" to
                    """{"has_token_plan":true,"op_group_id":"2118534767413568379","op_credit_summary":{"total_remaining_amount":"12"},"base_resp":{"status_code":0}}""",
                "/v1/api/openplatform/coding_plan/remains" to
                    """{"model_remains":[{"model_name":"general","current_interval_remaining_percent":75}],"base_resp":{"status_code":0}}""",
            )
            .use { server ->
                val account =
                    server
                        .client()
                        .fetchAccount("at", MiniMaxRegion.GLOBAL, "1", includeTokenPlan = true)

                assertEquals(true, account.hasTokenPlan)
                assertTrue(account.tokenPlanJson!!.contains("current_interval_remaining_percent"))
                server.request()
                server.request()
                val remains = server.request()
                assertEquals("2118534767413568379", remains.groupId)
                assertEquals("Bearer at", remains.authorization)
            }
    }

    @Test
    fun anExpiredLoginIsAnAuthFailure() {
        AgentServer(
                "/v1/api/user/info" to """{"statusInfo":{"code":1000048,"message":"need login"}}""",
            )
            .use { server ->
                val exception =
                    assertFailsWith<MiniMaxQuotaException> {
                        server.client().fetchUserId("at", MiniMaxRegion.GLOBAL)
                    }
                assertEquals(401, exception.statusCode)
                assertFalse(exception.message.orEmpty().contains("at"))
            }
    }

    private class Captured(
        val method: String,
        val path: String,
        val query: String,
        val body: String,
        val authorization: String?,
        val userAgent: String?,
        val yy: String?,
        val signature: String?,
        val groupId: String?,
    )

    private class AgentServer(vararg replies: Pair<String, String>) : AutoCloseable {
        private val replies = replies.toMap()
        private val requests = LinkedBlockingQueue<Captured>()
        private val server =
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
                createContext("/") { exchange ->
                    val headers = exchange.requestHeaders
                    requests +=
                        Captured(
                            exchange.requestMethod,
                            exchange.requestURI.rawPath,
                            exchange.requestURI.rawQuery.orEmpty(),
                            exchange.requestBody.use { it.readBytes().decodeToString() },
                            headers.getFirst("Authorization"),
                            headers.getFirst("User-Agent"),
                            headers.getFirst("yy"),
                            headers.getFirst("x-signature"),
                            headers.getFirst("X-Group-Id"),
                        )
                    val reply = this@AgentServer.replies[exchange.requestURI.rawPath]
                    val bytes = (reply ?: "{}").toByteArray()
                    exchange.sendResponseHeaders(
                        if (reply == null) 404 else 200,
                        bytes.size.toLong(),
                    )
                    exchange.responseBody.use { it.write(bytes) }
                }
                start()
            }
        private val uri: URI = URI.create("http://127.0.0.1:${server.address.port}")

        fun client() =
            MiniMaxCodeAccountClient(
                nowMs = { NOW },
                timezoneOffsetSeconds = { 3600 },
                osName = "darwin",
                hosts = { MiniMaxCodeHosts(uri, uri, uri) },
            )

        fun request(): Captured = requests.remove()

        fun pollRequest(): Captured? = requests.poll()

        override fun close() = server.stop(0)
    }

    private companion object {
        const val NOW = 1_794_037_149_123L

        // Live responses (2026-11-07) for an account with purchased Credits and two check-ins,
        // without personal details.
        val USER_INFO =
            "/v1/api/user/info" to
                """{"data":{"userInfo":{"userID":"AnMzVWzo6XJo","realUserID":"575100920747536392"}},"statusInfo":{"code":0,"message":"Success"}}"""
        val EXTRA_INFO =
            "/matrix/api/v1/user/get_user_extra_info" to
                """{"user_extra":{},"workspaces":[{"workspace_id":0,"workspace_name":"Default","workspace_type":0,"status":0,"selected":true,"personal_plan_type":1,"total_remains_credit":0,"op_group_id":"2118534767413568379","member_num":1,"opcredit_balance":3144,"is_migrated_to_op":true,"has_token_plan":false,"upgrade_action":"Month"}],"base_resp":{"status_code":0,"status_msg":"success"}}"""
        const val MEMBERSHIP_WITHOUT_PLAN =
            """{"plan_type":1,"plan_name":"","next_plan_name":"","will_renewal":false,"expires_at":0,"total_remains_credit":3144,"pending_credit":0,"purchase_source":0,"can_upgrade":false,"is_pro_builder":false,"daily_login_gift_credit_remaining":0,"is_migrated_to_op":true,"op_group_id":"2118534767413568379","has_token_plan":false,"opcredit_balance":3144,"upgrade_action":"Month","op_credit_summary":{"total_remaining_amount":"3944.267","purchased_remaining_amount":"3144.267","free_remaining_amount":"800"},"base_resp":{"status_code":0,"status_msg":"success"},"subscription_type":"none"}"""
    }
}
