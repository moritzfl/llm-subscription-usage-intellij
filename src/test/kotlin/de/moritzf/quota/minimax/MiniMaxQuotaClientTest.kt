package de.moritzf.quota.minimax

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

class MiniMaxQuotaClientTest {
    @Test
    fun parseQuotaUsesExplicitUsedCount() {
        val body = """
            {
              "base_resp": {"status_code": 0, "status_msg": "ok"},
              "model_remains": [{
                "current_interval_total_count": 300,
                "current_interval_used_count": 75,
                "start_time": 1770000000,
                "end_time": 1770018000,
                "current_subscribe_title": "MiniMax Coding Pro"
              }]
            }
        """.trimIndent()

        val quota = MiniMaxQuotaClient.parseQuota(body, MiniMaxRegion.GLOBAL)

        assertEquals("MiniMax Coding Pro (GLOBAL)", quota.plan)
        val usage = assertNotNull(quota.sessionUsage)
        assertEquals(75, usage.used)
        assertEquals(300, usage.limit)
        assertEquals(25.0, usage.usagePercent)
        assertEquals(18_000_000, usage.periodDurationMs)
        assertNull(quota.weeklyUsage)
    }

    @Test
    fun parseQuotaComputesUsedFromRemainingAlias() {
        val body = """
            {
              "base_resp": {"status_code": 0},
              "model_remains": [{
                "current_interval_total_count": 100,
                "current_interval_remaining_count": 40
              }]
            }
        """.trimIndent()

        val quota = MiniMaxQuotaClient.parseQuota(body, MiniMaxRegion.GLOBAL)

        val usage = assertNotNull(quota.sessionUsage)
        assertEquals(60, usage.used)
        assertEquals(60.0, usage.usagePercent)
        assertEquals("MiniMax Coding Lite (GLOBAL)", quota.plan)
        assertNull(quota.weeklyUsage)
    }

    @Test
    fun parseQuotaTreatsUsageCountAsRemaining() {
        val body = """
            {
              "base_resp": {"status_code": 0},
              "model_remains": [{
                "current_interval_total_count": 300,
                "current_interval_usage_count": 120
              }]
            }
        """.trimIndent()

        val quota = MiniMaxQuotaClient.parseQuota(body, MiniMaxRegion.GLOBAL)

        val usage = assertNotNull(quota.sessionUsage)
        assertEquals(180, usage.used)
        assertEquals(60.0, usage.usagePercent)
    }

    @Test
    fun parseQuotaUsesRemainingPercentWhenCountsAreZero() {
        val body = """
            {
              "base_resp": {"status_code": 0, "status_msg": "success"},
              "model_remains": [{
                "model_name": "general",
                "start_time": 1782381600000,
                "end_time": 1782399600000,
                "remains_time": 2669853,
                "current_interval_total_count": 0,
                "current_interval_usage_count": 0,
                "current_interval_remaining_percent": 10,
                "current_interval_status": 1,
                "current_weekly_total_count": 0,
                "current_weekly_usage_count": 0,
                "current_weekly_remaining_percent": 69,
                "current_weekly_status": 1,
                "weekly_start_time": 1782086400000,
                "weekly_end_time": 1782691200000,
                "weekly_remains_time": 294269853
              }]
            }
        """.trimIndent()

        val quota = MiniMaxQuotaClient.parseQuota(body, MiniMaxRegion.GLOBAL)

        assertEquals("MiniMax Token Plan (GLOBAL)", quota.plan)
        val session = assertNotNull(quota.sessionUsage)
        assertEquals(0, session.used)
        assertEquals(0, session.limit)
        assertEquals(90.0, session.usagePercent)
        assertEquals(18_000_000, session.periodDurationMs)
        assertEquals(1_782_399_600_000, session.resetsAt?.toEpochMilliseconds())
        val weekly = assertNotNull(quota.weeklyUsage)
        assertEquals(31.0, weekly.usagePercent)
        assertEquals(1_782_691_200_000, weekly.resetsAt?.toEpochMilliseconds())
        assertEquals(mapOf("session" to 0.90, "weekly" to 0.31), quota.activityWindows())
        assertEquals(0.90, quota.usageFraction())
    }

    @Test
    fun parseQuotaPrefersGeneralOverVideo() {
        val body = """
            {
              "base_resp": {"status_code": 0},
              "model_remains": [
                {
                  "model_name": "video",
                  "current_interval_remaining_percent": 0,
                  "current_interval_status": 2
                },
                {
                  "model_name": "general",
                  "current_interval_remaining_percent": 40,
                  "current_interval_status": 1,
                  "current_weekly_remaining_percent": 80,
                  "current_weekly_status": 1
                }
              ]
            }
        """.trimIndent()

        val quota = MiniMaxQuotaClient.parseQuota(body, MiniMaxRegion.GLOBAL)

        assertEquals(60.0, assertNotNull(quota.sessionUsage).usagePercent)
        assertEquals(20.0, assertNotNull(quota.weeklyUsage).usagePercent)
    }

    @Test
    fun parseQuotaMarksExhaustedStatusAs100() {
        val body = """
            {
              "base_resp": {"status_code": 0},
              "model_remains": [{
                "model_name": "general",
                "current_interval_remaining_percent": 5,
                "current_interval_status": 2,
                "current_weekly_remaining_percent": 50,
                "current_weekly_status": 1
              }]
            }
        """.trimIndent()

        val quota = MiniMaxQuotaClient.parseQuota(body, MiniMaxRegion.GLOBAL)

        assertEquals(100.0, assertNotNull(quota.sessionUsage).usagePercent)
        assertEquals(50.0, assertNotNull(quota.weeklyUsage).usagePercent)
    }

    @Test
    fun parseQuotaSkipsUnlimitedWindows() {
        val body = """
            {
              "base_resp": {"status_code": 0},
              "model_remains": [{
                "model_name": "general",
                "current_interval_remaining_percent": 100,
                "current_interval_status": 3,
                "current_weekly_remaining_percent": 70,
                "current_weekly_status": 1
              }]
            }
        """.trimIndent()

        val quota = MiniMaxQuotaClient.parseQuota(body, MiniMaxRegion.GLOBAL)

        assertNull(quota.sessionUsage)
        assertEquals(30.0, assertNotNull(quota.weeklyUsage).usagePercent)
    }

    @Test
    fun parseQuotaOmitsNullWeeklyWindow() {
        val body = """
            {
              "base_resp": {"status_code": 0},
              "model_remains": [{
                "current_interval_total_count": 300,
                "current_interval_used_count": 30
              }]
            }
        """.trimIndent()

        val quota = MiniMaxQuotaClient.parseQuota(body, MiniMaxRegion.GLOBAL)

        assertNotNull(quota.sessionUsage)
        assertNull(quota.weeklyUsage)
    }

    @Test
    fun parseQuotaTreatsTokenPlanRemainsTimeAsMillis() {
        val before = Clock.System.now()
        val body = """
            {
              "base_resp": {"status_code": 0},
              "model_remains": [{
                "model_name": "general",
                "start_time": 1782381600000,
                "current_interval_remaining_percent": 50,
                "remains_time": 2669853,
                "current_weekly_remaining_percent": 80,
                "weekly_remains_time": 294269853
              }]
            }
        """.trimIndent()

        val quota = MiniMaxQuotaClient.parseQuota(body, MiniMaxRegion.GLOBAL)
        val after = Clock.System.now()

        val sessionReset = assertNotNull(quota.sessionUsage?.resetsAt)
        assertTrue(sessionReset >= before.plus(44.minutes))
        assertTrue(sessionReset <= after.plus(45.minutes))
        val weeklyReset = assertNotNull(quota.weeklyUsage?.resetsAt)
        assertTrue(weeklyReset >= before.plus(3.days))
        assertTrue(weeklyReset <= after.plus(4.days))
    }

    @Test
    fun parseQuotaReportsApiError() {
        val body = """{"base_resp":{"status_code":1001,"status_msg":"no plan"},"model_remains":[]}"""

        val exception = assertFailsWith<MiniMaxQuotaException> {
            MiniMaxQuotaClient.parseQuota(body, MiniMaxRegion.CN)
        }

        assertEquals("MiniMax API error: no plan", exception.message)
        assertEquals(body, exception.rawBody)
    }

    @Test
    fun parseQuotaReportsMalformedPayload() {
        val body = "not json"

        val exception = assertFailsWith<MiniMaxQuotaException> {
            MiniMaxQuotaClient.parseQuota(body, MiniMaxRegion.GLOBAL)
        }

        assertEquals("Could not parse usage data.", exception.message)
        assertEquals(body, exception.rawBody)
    }

    @Test
    fun fetchQuotaFallsBackToLegacyCodingPlan() {
        TestMiniMaxServer(
            responses = mapOf(
                "/v1/token_plan/remains" to (404 to """{"error":"not found"}"""),
                "/v1/coding_plan/remains" to (200 to """
                    {
                      "base_resp": {"status_code": 0},
                      "model_remains": [{
                        "current_interval_total_count": 300,
                        "current_interval_used_count": 75,
                        "current_subscribe_title": "MiniMax Coding Pro"
                      }]
                    }
                """.trimIndent()),
            ),
        ).use { server ->
            val client = MiniMaxQuotaClient(
                httpClient = HttpClient.newHttpClient(),
                endpointsByRegion = {
                    listOf(
                        server.uri("/v1/token_plan/remains"),
                        server.uri("/v1/coding_plan/remains"),
                    )
                },
            )

            val quota = client.fetchQuota("sk-test", MiniMaxRegion.GLOBAL)

            assertEquals("MiniMax Coding Pro (GLOBAL)", quota.plan)
            assertEquals(25.0, assertNotNull(quota.sessionUsage).usagePercent)
            assertEquals("/v1/token_plan/remains", server.requests.poll(2, TimeUnit.SECONDS)?.path)
            assertEquals("/v1/coding_plan/remains", server.requests.poll(2, TimeUnit.SECONDS)?.path)
        }
    }

    @Test
    fun fetchQuotaDoesNotFallbackOnUnauthorized() {
        TestMiniMaxServer(
            responses = mapOf(
                "/v1/token_plan/remains" to (401 to """{"error":"expired"}"""),
                "/v1/coding_plan/remains" to (200 to """{"base_resp":{"status_code":0},"model_remains":[]}"""),
            ),
        ).use { server ->
            val client = MiniMaxQuotaClient(
                httpClient = HttpClient.newHttpClient(),
                endpointsByRegion = {
                    listOf(
                        server.uri("/v1/token_plan/remains"),
                        server.uri("/v1/coding_plan/remains"),
                    )
                },
            )

            val exception = assertFailsWith<MiniMaxQuotaException> {
                client.fetchQuota("sk-test", MiniMaxRegion.GLOBAL)
            }

            assertEquals(401, exception.statusCode)
            assertEquals("/v1/token_plan/remains", server.requests.poll(2, TimeUnit.SECONDS)?.path)
            assertNull(server.requests.poll(200, TimeUnit.MILLISECONDS))
        }
    }

    private class TestMiniMaxServer(
        private val responses: Map<String, Pair<Int, String>>,
    ) : AutoCloseable {
        val requests = LinkedBlockingQueue<CapturedRequest>()
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

        init {
            server.createContext("/") { exchange ->
                val path = exchange.requestURI.rawPath
                requests += CapturedRequest(path)
                val (status, body) = responses[path] ?: (404 to "{}")
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { output -> output.write(bytes) }
            }
            server.start()
        }

        fun uri(path: String): URI {
            return URI.create("http://127.0.0.1:${server.address.port}$path")
        }

        override fun close() {
            server.stop(0)
        }
    }

    private data class CapturedRequest(val path: String)
}
