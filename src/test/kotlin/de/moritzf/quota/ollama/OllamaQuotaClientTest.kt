package de.moritzf.quota.ollama

import de.moritzf.quota.shared.JsonSupport
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
}
