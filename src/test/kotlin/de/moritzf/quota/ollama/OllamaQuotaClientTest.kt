package de.moritzf.quota.ollama

import de.moritzf.quota.shared.JsonSupport
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OllamaQuotaClientTest {
    @Test
    fun parseBalanceReadsMonthlyIncludedUsageAndBillingPeriod() {
        val quota = OllamaQuotaClient.parseQuota(MONTHLY_BALANCE)
        val monthly = assertNotNull(quota.monthlyUsage)
        assertEquals(0.35, monthly.usagePercent, absoluteTolerance = 0.0001)
        assertEquals(0.21, assertNotNull(monthly.usedAmountUsd), absoluteTolerance = 0.0001)
        assertEquals(60.0, monthly.allowanceUsd)
        assertEquals(Instant.parse("2026-10-02T18:08:50Z"), monthly.periodStartedAt)
        assertEquals(Instant.parse("2026-11-02T18:08:50Z"), monthly.resetsAt)
        assertNull(quota.sessionUsage)
        assertNull(quota.weeklyUsage)
        assertTrue(quota.hasUsageState())
        assertEquals(0.0035, assertNotNull(quota.usageFraction()), absoluteTolerance = 0.000001)
        assertEquals(0.0035, quota.activityWindows().getValue("monthly"), absoluteTolerance = 0.000001)
        assertEquals(quota, JsonSupport.json.decodeFromString<OllamaQuota>(JsonSupport.json.encodeToString(quota)))
    }

    @Test
    fun fetchQuotaUsesBalanceEndpointAndPreservesProviderJson() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = mutableListOf<Pair<String, String?>>()
        server.createContext("/api/balance") { exchange ->
            requests += exchange.requestMethod to exchange.requestHeaders.getFirst("Authorization")
            val bytes = MONTHLY_BALANCE.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val endpoint = URI.create("http://127.0.0.1:${server.address.port}")
                .resolve(OllamaQuotaClient.DEFAULT_ENDPOINT.rawPath)
            val quota = OllamaQuotaClient(endpoint = endpoint).fetchQuota(" test-key ")
            assertEquals("GET", requests.single().first)
            assertEquals("Bearer test-key", requests.single().second)
            assertEquals(0.35, assertNotNull(quota.monthlyUsage).usagePercent, absoluteTolerance = 0.0001)
            assertEquals(MONTHLY_BALANCE, quota.rawJson)
            assertNotNull(quota.fetchedAt)
            val adjusted = OllamaQuotaClient.applyConfiguredMonthlyReset(
                quota,
                Instant.parse("2026-10-15T00:00:00Z"),
                Instant.parse("2026-10-07T17:00:00Z"),
            )
            assertEquals(Instant.parse("2026-11-02T18:08:50Z"), assertNotNull(adjusted.monthlyUsage).resetsAt)
            assertEquals(MONTHLY_BALANCE, adjusted.rawJson)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun parseBalanceConvertsLegacyRemainingPercentWithoutFractionHeuristic() {
        val quota = OllamaQuotaClient.parseQuota(
            """
                {"included": {
                  "session": {"remaining_percent": 75, "resets_at": "2026-10-07T17:00:00Z"},
                  "weekly": {"remaining_percent": 0.5, "resets_at": "2026-10-12T00:00:00Z"}
                }, "purchased": {"balance_usd": 25}}
            """.trimIndent(),
        )
        assertEquals(25.0, assertNotNull(quota.sessionUsage).usagePercent)
        assertEquals(Instant.parse("2026-10-07T17:00:00Z"), quota.sessionUsage.resetsAt)
        assertEquals(99.5, assertNotNull(quota.weeklyUsage).usagePercent)
        assertEquals(Instant.parse("2026-10-12T00:00:00Z"), quota.weeklyUsage.resetsAt)
        assertNull(quota.monthlyUsage)
    }

    @Test
    fun parseBalanceKeepsValidLegacyWindowWhenSiblingChanges() {
        val quota = OllamaQuotaClient.parseQuota(
            """{"included":{"session":{"remaining_percent":{}},"weekly":{"remaining_percent":40}},"purchased":"changed"}""",
        )
        assertNull(quota.sessionUsage)
        assertEquals(60.0, assertNotNull(quota.weeklyUsage).usagePercent)
        assertNull(quota.weeklyUsage.resetsAt)
    }

    @Test
    fun parseBalanceHandlesFullAndEmptyIncludedCredits() {
        for ((remaining, percent) in listOf(60.0 to 0.0, 0.0 to 100.0)) {
            val quota = OllamaQuotaClient.parseQuota(
                """{"included":{"balance_usd":$remaining,"allowance_usd":60}}""",
            )
            val monthly = assertNotNull(quota.monthlyUsage)
            assertEquals(percent, monthly.usagePercent)
            assertEquals(60.0 - remaining, monthly.usedAmountUsd)
            assertNull(monthly.resetsAt)
        }
    }

    @Test
    fun parseBalanceRejectsMissingOrInvalidAllowance() {
        for (included in listOf(
            """{"balance_usd":10}""", """{"allowance_usd":60}""",
            """{"balance_usd":0,"allowance_usd":0}""", """{"balance_usd":0,"allowance_usd":-1}""",
            """{"balance_usd":0,"allowance_usd":"NaN"}""",
        )) {
            assertFailsWith<OllamaQuotaException> { OllamaQuotaClient.parseQuota("""{"included":$included}""") }
        }
    }

    @Test
    fun parseRangeBasedHistoryDoesNotInventQuotaLimits() {
        val body = """
            {
              "range": "7d", "scope": "self", "granularity": "day",
              "from": "2026-09-30T00:00:00Z", "until": "2026-10-07T16:56:43.814977816Z",
              "totals": {
                "request_count": 1257, "usage_usd": 2.85837,
                "input_tokens": 19842012, "cached_input_tokens": 13793244, "output_tokens": 899968
              },
              "buckets": [{
                "from": "2026-10-07T00:00:00Z", "until": "2026-10-07T16:56:43.814977816Z",
                "partial": true, "request_count": 1, "usage_usd": 1.9E-4,
                "input_tokens": 548, "cached_input_tokens": 0, "output_tokens": 20
              }]
            }
        """.trimIndent()
        assertFailsWith<OllamaQuotaException> { OllamaQuotaClient.parseQuota(body) }
    }

    @Test
    fun buildRawResponseAddsResetsAtRoot() {
        val usage = """{"activity":{"cost":"0"},"limits":{"session":{"usage":0.1},"weekly":{"usage":0.2}}}"""
        val raw = OllamaQuotaClient.buildRawResponse(
            usage,
            Instant.parse("2026-08-10T10:00:00Z"),
            Instant.parse("2026-08-17T00:00:00Z"),
        )
        val parsed = JsonSupport.json.parseToJsonElement(raw).jsonObject
        assertEquals("0", parsed["activity"]!!.jsonObject["cost"]!!.jsonPrimitive.content)
        assertEquals(0.1, parsed["limits"]!!.jsonObject["session"]!!.jsonObject["usage"]!!.jsonPrimitive.content.toDouble(), absoluteTolerance = 0.0001)
        val resets = parsed["resets_at"]!!.jsonObject
        assertEquals("2026-08-10T10:00:00Z", resets["session"]!!.jsonPrimitive.content)
        assertEquals("2026-08-17T00:00:00Z", resets["weekly"]!!.jsonPrimitive.content)
    }

    @Test
    fun buildRawResponseOmitsMissingResetWindows() {
        val raw = OllamaQuotaClient.buildRawResponse(
            """{"limits":{"session":{"usage":0.9}}}""",
            Instant.parse("2026-08-10T10:00:00Z"),
            null,
        )
        val parsed = JsonSupport.json.parseToJsonElement(raw).jsonObject
        val resets = parsed["resets_at"]!!.jsonObject
        assertEquals("2026-08-10T10:00:00Z", resets["session"]!!.jsonPrimitive.content)
        assertNull(resets["weekly"])
        assertTrue("limits" in parsed)
    }

    @Test
    fun parseQuotaConvertsUsageFractionsToPercent() {
        val json = """
            {
              "activity": {
                "cost": "0.00000",
                "period": {
                  "type": "last_4_weeks",
                  "starting_at": "2026-07-06T00:00:00Z",
                  "ending_at": "2026-07-29T12:45:50Z"
                },
                "models": []
              },
              "limits": {
                "session": {
                  "usage": 0.046,
                  "models": [
                    { "name": "glm-5.2", "request_count": 34 }
                  ]
                },
                "weekly": {
                  "usage": 0.051,
                  "models": [
                    { "name": "glm-5.2", "request_count": 254 }
                  ]
                }
              }
            }
        """.trimIndent()

        val now = Instant.parse("2026-08-10T05:30:42Z")
        val quota = OllamaQuotaClient.parseQuota(json, now)

        val session = assertNotNull(quota.sessionUsage)
        assertEquals(4.6, session.usagePercent, absoluteTolerance = 0.0001)
        assertEquals(Instant.parse("2026-08-10T10:00:00Z"), session.resetsAt)
        val weekly = assertNotNull(quota.weeklyUsage)
        assertEquals(5.1, weekly.usagePercent, absoluteTolerance = 0.0001)
        assertEquals(Instant.parse("2026-08-17T00:00:00Z"), weekly.resetsAt)
    }

    @Test
    fun parseQuotaAcceptsPercentValuesAboveOne() {
        val json = """
            {
              "limits": {
                "session": { "usage": 12.5 },
                "weekly": { "usage": 50 }
              }
            }
        """.trimIndent()

        val quota = OllamaQuotaClient.parseQuota(json)

        assertEquals(12.5, assertNotNull(quota.sessionUsage).usagePercent, absoluteTolerance = 0.0001)
        assertEquals(50.0, assertNotNull(quota.weeklyUsage).usagePercent, absoluteTolerance = 0.0001)
    }

    @Test
    fun parseQuotaWithMissingLimitsThrows() {
        val exception = assertFailsWith<OllamaQuotaException> {
            OllamaQuotaClient.parseQuota("""{"activity":{}}""")
        }
        assertEquals(200, exception.statusCode)
    }

    @Test
    fun parseQuotaAcceptsMonthlyCreditPlan() {
        val json = """
            {
              "activity": {
                "cost": "0.00000",
                "period": {
                  "type": "last_4_weeks",
                  "starting_at": "2026-08-10T00:00:00Z",
                  "ending_at": "2026-09-03T17:42:59.21950703Z"
                },
                "models": []
              },
              "limits": {
                "monthly": {
                  "usage": 0,
                  "models": [
                    { "name": "web search", "request_count": 1 },
                    { "name": "deepseek-v4-flash:0731", "request_count": 1 }
                  ]
                }
              }
            }
        """.trimIndent()

        val now = Instant.parse("2026-09-03T17:42:59Z")
        val quota = OllamaQuotaClient.parseQuota(json, now)

        assertNull(quota.sessionUsage)
        assertNull(quota.weeklyUsage)
        val monthly = assertNotNull(quota.monthlyUsage)
        assertEquals(0.0, monthly.usagePercent, absoluteTolerance = 0.0001)
        assertNull(monthly.periodStartedAt)
        assertNull(monthly.resetsAt)
        assertTrue(quota.hasUsageState())
        assertEquals(0.0, quota.usageFraction()!!, absoluteTolerance = 0.0001)
        assertEquals(mapOf("monthly" to 0.0), quota.activityWindows())
    }

    @Test
    fun parseQuotaUsesFutureActivityEndAsMonthlyReset() {
        val quota = OllamaQuotaClient.parseQuota(
            """{"activity":{"period":{"starting_at":"2026-08-10T00:00:00Z","ending_at":"2026-09-20T00:00:00Z"}},"limits":{"monthly":{"usage":0.1}}}""",
            Instant.parse("2026-09-03T17:00:00Z"),
        )

        val monthly = assertNotNull(quota.monthlyUsage)
        assertNull(monthly.periodStartedAt)
        assertEquals(Instant.parse("2026-09-20T00:00:00Z"), monthly.resetsAt)
    }

    @Test
    fun parseQuotaConvertsMonthlyUsageFractionToPercent() {
        val quota = OllamaQuotaClient.parseQuota(
            """{"limits":{"monthly":{"usage":0.25,"resets_at":"2026-09-10T00:00:00Z"}}}""",
        )

        val monthly = assertNotNull(quota.monthlyUsage)
        assertEquals(25.0, monthly.usagePercent, absoluteTolerance = 0.0001)
        assertEquals(Instant.parse("2026-09-10T00:00:00Z"), monthly.resetsAt)
        assertNull(quota.sessionUsage)
        assertNull(quota.weeklyUsage)
    }

    @Test
    fun parseQuotaAllowsSessionOnly() {
        val quota = OllamaQuotaClient.parseQuota(
            """{"limits":{"session":{"usage":0.9}}}""",
        )
        assertEquals(90.0, assertNotNull(quota.sessionUsage).usagePercent, absoluteTolerance = 0.0001)
        assertNull(quota.weeklyUsage)
    }

    @Test
    fun parseQuotaIgnoresUnknownAndReshapedSiblingSections() {
        val json = """
            {
              "plan": { "name": "pro" },
              "credits": { "granted": "unknown-shape" },
              "activity": "not-an-object",
              "limits": {
                "session": { "usage": { "used": 4, "limit": 100 } },
                "weekly": {
                  "usage": 0.051,
                  "models": [ { "name": "glm-5.2", "request_count": "many" } ],
                  "future_field": { "nested": true }
                },
                "daily": { "usage": 0.2 }
              }
            }
        """.trimIndent()

        val quota = OllamaQuotaClient.parseQuota(json)

        // Reshaped session block drops only itself; weekly usage still shows.
        // Extra root fields (plan/credits/activity) are ignored entirely.
        assertNull(quota.sessionUsage)
        assertEquals(5.1, assertNotNull(quota.weeklyUsage).usagePercent, absoluteTolerance = 0.0001)
    }

    @Test
    fun parseQuotaAcceptsNumericStringUsage() {
        val quota = OllamaQuotaClient.parseQuota(
            """{"limits":{"session":{"usage":"0.25"},"weekly":{"usage":"7.5"}}}""",
        )

        assertEquals(25.0, assertNotNull(quota.sessionUsage).usagePercent, absoluteTolerance = 0.0001)
        assertEquals(7.5, assertNotNull(quota.weeklyUsage).usagePercent, absoluteTolerance = 0.0001)
    }

    @Test
    fun parseQuotaReadsResetTimestampWhenPresent() {
        val now = Instant.parse("2026-08-10T05:30:42Z")
        val quota = OllamaQuotaClient.parseQuota(
            """{"limits":{"session":{"usage":0.1,"resets_at":"2026-07-29T18:00:00Z"},"weekly":{"usage":0.2,"resets_at":"nonsense"}}}""",
            now,
        )

        assertEquals(
            Instant.parse("2026-07-29T18:00:00Z"),
            assertNotNull(quota.sessionUsage).resetsAt,
        )
        // Unparsable timestamp falls back to the global weekly schedule.
        assertEquals(Instant.parse("2026-08-17T00:00:00Z"), assertNotNull(quota.weeklyUsage).resetsAt)
        assertEquals(20.0, assertNotNull(quota.weeklyUsage).usagePercent, absoluteTolerance = 0.0001)
    }

    @Test
    fun applyConfiguredMonthlyResetFillsMissingMonthlyStamp() {
        val quota = OllamaQuotaClient.parseQuota(
            """{"limits":{"monthly":{"usage":0.769}}}""",
            Instant.parse("2026-09-19T09:00:00Z"),
        )
        val applied = OllamaQuotaClient.applyConfiguredMonthlyReset(
            quota,
            Instant.parse("2026-10-02T18:08:50Z"),
            Instant.parse("2026-09-19T09:00:00Z"),
        )
        assertEquals(Instant.parse("2026-10-02T18:08:50Z"), assertNotNull(applied.monthlyUsage).resetsAt)
        assertTrue(applied.rawJson!!.contains("2026-10-02T18:08:50Z"))
    }

    @Test
    fun applyConfiguredMonthlyResetKeepsApiStamp() {
        val quota = OllamaQuotaClient.parseQuota(
            """{"limits":{"monthly":{"usage":0.1,"resets_at":"2026-09-10T00:00:00Z"}}}""",
        )
        val applied = OllamaQuotaClient.applyConfiguredMonthlyReset(
            quota,
            Instant.parse("2026-10-02T18:08:50Z"),
            Instant.parse("2026-09-19T09:00:00Z"),
        )
        assertEquals(Instant.parse("2026-09-10T00:00:00Z"), assertNotNull(applied.monthlyUsage).resetsAt)
    }

    @Test
    fun applyConfiguredMonthlyResetIgnoresLegacySessionPlans() {
        val quota = OllamaQuotaClient.parseQuota("""{"limits":{"session":{"usage":0.1}}}""")
        val applied = OllamaQuotaClient.applyConfiguredMonthlyReset(
            quota,
            Instant.parse("2026-10-02T18:08:50Z"),
            Instant.parse("2026-09-19T09:00:00Z"),
        )
        assertNull(applied.monthlyUsage)
        assertNotNull(applied.sessionUsage)
    }

    @Test
    fun parseQuotaClampsOutOfRangeUsage() {
        val quota = OllamaQuotaClient.parseQuota(
            """{"limits":{"session":{"usage":-0.5},"weekly":{"usage":250}}}""",
        )

        assertEquals(0.0, assertNotNull(quota.sessionUsage).usagePercent, absoluteTolerance = 0.0001)
        assertEquals(100.0, assertNotNull(quota.weeklyUsage).usagePercent, absoluteTolerance = 0.0001)
    }

    private companion object {
        // The monthly balance is separate from the rolling 7d usage-history totals.
        val MONTHLY_BALANCE = """
            {
              "included": {
                "balance_usd": 59.79,
                "allowance_usd": 60,
                "period": {"from": "2026-10-02T18:08:50Z", "until": "2026-11-02T18:08:50Z"}
              },
              "purchased": {"balance_usd": 0}
            }
        """.trimIndent()
    }
}
