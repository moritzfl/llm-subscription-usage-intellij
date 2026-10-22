package de.moritzf.quota.cursor

import com.sun.net.httpserver.HttpServer
import de.moritzf.quota.shared.JsonSupport
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.serialization.json.jsonObject

class CursorGrokAndUnifiedPlanTest {
    private val now = Instant.parse("2026-10-19T12:00:00Z")
    private val summary =
        """{"individualUsage":{"plan":{"autoPercentUsed":10,"apiPercentUsed":5}}}"""

    @Test
    fun paidGrokAllowanceResetsWeekly() {
        val quota =
            CursorQuotaClient.parseUsageSummary(
                summary,
                null,
                null,
                sandJson =
                    """{"currentPeriodStart":"2026-10-17T07:57:50.647Z","nextResetTimestampUtc":"2026-10-24T07:57:50.647Z",
                    "usagePercent":22.9,"hasAvailableUsage":true,"includedLimitZero":false}""",
                now = now,
            )

        assertEquals(
            CursorGrokUsage(22.9, Instant.parse("2026-10-24T07:57:50.647Z")),
            quota.grokUsage,
        )
        assertEquals(0.229, quota.activityWindows()["grok"]!!, 0.0001)
    }

    @Test
    fun grokTrialHasNoResetAndDisappearsWhenItEnds() {
        fun quota(expires: String) =
            CursorQuotaClient.parseUsageSummary(
                summary,
                null,
                null,
                sandJson =
                    """{"nextResetTimestampUtc":"2026-10-21T09:12:32.776Z","usagePercent":100,
                    "includedLimitZero":true,"sandTrialExpiresAt":"$expires"}""",
                now = now,
            )

        assertEquals(
            CursorGrokUsage(100.0, trialEndsAt = Instant.parse("2026-10-21T09:12:32.776Z")),
            quota("2026-10-21T09:12:32.776Z").grokUsage,
        )
        assertNull(quota("2026-10-18T09:12:32.776Z").grokUsage)
    }

    @Test
    fun grokWithoutAnAllowanceOrWithLegacyRequestsIsHidden() {
        val noLimit =
            CursorQuotaClient.parseUsageSummary(
                summary,
                null,
                null,
                sandJson = """{"usagePercent":0,"includedLimitZero":true}""",
                now = now,
            )
        val legacy =
            CursorQuotaClient.parseUsageSummary(
                """{"individualUsage":{"plan":{"used":100,"limit":2000}}}""",
                null,
                """{"gpt-4":{"numRequestsTotal":12,"maxRequestUsage":500}}""",
                sandJson = """{"usagePercent":40,"hasNonZeroIncludedLimit":true}""",
                now = now,
            )

        assertNull(noLimit.grokUsage)
        assertNull(legacy.grokUsage)
    }

    @Test
    fun combinedPlanUsesUsageStatusSpendAndTheWeeklyPool() {
        val quota =
            CursorQuotaClient.parseUsageSummary(
                """{"membershipType":"unified_ultra","limitType":"user","billingCycleStart":"2026-10-01T00:00:00.000Z",
                "billingCycleEnd":"2026-11-01T00:00:00.000Z","isUnlimited":false,
                "individualUsage":{"plan":{"totalPercentUsed":100},"onDemand":{"enabled":true,"used":0,"limit":1000,"remaining":1000}}}""",
                null,
                null,
                sandJson =
                    """{"usagePercent":100,"nextResetTimestampUtc":"2026-10-20T06:35:09Z","hasNonZeroIncludedLimit":true,
                    "isUnified":true,"cursorPlanName":"Ultra"}""",
                unifiedJson =
                    """{"availability":"available","monthlyUsage":{"usedCents":93,"limitCents":1000,"creditsUsedCents":0,
                    "periodStart":"2026-10-01T00:00:00.000Z","resetsAt":"2026-11-01T00:00:00.000Z"}}""",
                now = now,
            )

        assertEquals("Ultra", quota.planName)
        assertEquals(0.93, quota.onDemandUsage?.usedUsd)
        assertEquals(10.0, quota.onDemandUsage?.limitUsd)
        assertNull(quota.grokUsage, "the Grok Bot pool is the included pool, shown once")
        assertEquals(Instant.parse("2026-10-20T06:35:09Z"), quota.planUsage?.includedResetsAt())
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"), quota.planUsage?.billingCycleEnd)
    }

    @Test
    fun unusableUsageStatusKeepsTheSummarySpend() {
        fun quota(unified: String) =
            CursorQuotaClient.parseUsageSummary(
                """{"membershipType":"unified_plus","limitType":"user",
                "individualUsage":{"plan":{"totalPercentUsed":5},"onDemand":{"enabled":true,"used":250,"limit":1000}}}""",
                null,
                null,
                unifiedJson = unified,
                now = now,
            )

        for (unified in
            listOf(
                """{"availability":"identity_unlinked"}""",
                """{"availability":"available","monthlyUsage":{"usedCents":93,"periodStart":"2026-10-01T00:00:00Z","resetsAt":"2026-11-01T00:00:00Z"}}""",
                """{"availability":"available","monthlyUsage":{"usedCents":93,"limitCents":1000,"periodStart":"2026-09-01T00:00:00Z","resetsAt":"2026-10-01T00:00:00Z"}}""",
                "not json",
            )) {
            assertEquals(2.5, quota(unified).onDemandUsage?.usedUsd, unified)
        }
        assertEquals("Plus", quota("{}").planName)
    }

    @Test
    fun weeklyPlanInfoMovesTheIncludedResetOnTheApiPath() {
        val quota =
            CursorQuotaClient.parseQuota(
                """{"billingCycleEnd":"1793491200000","planUsage":{"autoPercentUsed":10,"apiPercentUsed":1}}""",
                """{"isMerged":true,"planInfo":{"planName":"Pro","includedUsagePeriod":"INCLUDED_USAGE_PERIOD_WEEKLY","includedUsageResetsAt":"1790500000000"}}""",
                null,
                sandJson =
                    """{"usagePercent":30,"hasNonZeroIncludedLimit":true,"nextResetTimestampUtc":"2026-10-24T00:00:00Z"}""",
                now = now,
            )
        val numeric =
            CursorQuotaClient.parseQuota(
                """{"planUsage":{"autoPercentUsed":10}}""",
                """{"planInfo":{"includedUsagePeriod":2,"includedUsageResetsAt":1790500000000}}""",
                null,
                now = now,
            )

        assertEquals(
            Instant.fromEpochMilliseconds(1790500000000),
            quota.planUsage?.includedResetsAt(),
        )
        assertNull(quota.grokUsage, "merged plans share one pool")
        assertEquals(
            Instant.fromEpochMilliseconds(1790500000000),
            numeric.planUsage?.weeklyResetsAt,
        )
    }

    @Test
    fun webPathCallsTheOptionalEndpointsAndKeepsTheirResponses() {
        TestCursorServer().use { server ->
            val quota =
                CursorQuotaClient(HttpClient.newHttpClient(), server.uri, server.uri)
                    .fetchQuota(
                        "token",
                        CursorAuth("token", sessionCookie = "user_01ABC%3A%3Atoken"),
                    )

            val paths = server.requests.map { it.first }
            assertTrue("/api/dashboard/v2/usage-status" in paths, paths.toString())
            assertTrue("/api/usage" !in paths, "combined plans skip the legacy request lookup")
            val sand = server.requests.single { it.first == "/api/dashboard/get-sand-usage-status" }
            assertEquals(server.uri.toString().trimEnd('/'), sand.second)
            assertEquals(0.93, quota.onDemandUsage?.usedUsd)
            val raw = JsonSupport.json.parseToJsonElement(quota.rawJson!!).jsonObject
            assertTrue("sandUsage" in raw && "unifiedUsage" in raw, raw.keys.toString())
        }
    }

    @Test
    fun rejectedOptionalEndpointsDoNotLookLikeAnExpiredSession() {
        TestCursorServer(sandStatus = 403, unifiedStatus = 307).use { server ->
            val quota =
                CursorQuotaClient(HttpClient.newHttpClient(), server.uri, server.uri)
                    .fetchQuota(
                        "token",
                        CursorAuth("token", sessionCookie = "user_01ABC%3A%3Atoken"),
                    )

            assertEquals(10.0, quota.planUsage?.autoPercentUsed)
            assertNull(quota.grokUsage)
        }
    }

    private class TestCursorServer(
        private val sandStatus: Int = 200,
        private val unifiedStatus: Int = 200,
    ) : AutoCloseable {
        val requests = CopyOnWriteArrayList<Pair<String, String?>>()
        private val server =
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val uri: URI

        init {
            server.createContext("/") { exchange ->
                val path = exchange.requestURI.path
                requests += path to exchange.requestHeaders.getFirst("Origin")
                val (status, body) =
                    when (path) {
                        "/api/usage-summary" ->
                            200 to
                                """{"membershipType":"unified_ultra","limitType":"user",
                                "individualUsage":{"plan":{"autoPercentUsed":10,"apiPercentUsed":2}}}"""
                        "/api/auth/me" -> 200 to """{"email":"me@example.com","sub":"user_01ABC"}"""
                        "/api/dashboard/v2/usage-status" ->
                            unifiedStatus to
                                """{"availability":"available","monthlyUsage":{"usedCents":93,"limitCents":1000,
                                "periodStart":"2020-01-01T00:00:00Z","resetsAt":"2099-01-01T00:00:00Z"}}"""
                        "/api/dashboard/get-sand-usage-status" ->
                            sandStatus to
                                """{"usagePercent":50,"hasNonZeroIncludedLimit":true,"isUnified":true}"""
                        else -> 404 to "{}"
                    }
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            uri = URI.create("http://127.0.0.1:${server.address.port}/")
        }

        override fun close() = server.stop(0)
    }
}
