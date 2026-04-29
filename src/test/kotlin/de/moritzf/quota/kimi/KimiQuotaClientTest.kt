package de.moritzf.quota.kimi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class KimiQuotaClientTest {
    @Test
    fun parseQuotaExtractsSessionAndTotalUsage() {
        val body = """
            {
              "usage": {"limit":"100", "remaining":"74", "resetTime":"2026-02-11T17:32:50.757941Z"},
              "limits": [{
                "window": {"duration":300, "timeUnit":"TIME_UNIT_MINUTE"},
                "detail": {"limit":"100", "remaining":"85", "resetTime":"2026-02-07T12:32:50.757941Z"}
              }],
              "user": {"membership": {"level":"LEVEL_INTERMEDIATE"}}
            }
        """.trimIndent()

        val quota = KimiQuotaClient.parseQuota(body)

        assertEquals("Kimi Code Intermediate", quota.plan)
        val session = assertNotNull(quota.sessionUsage)
        assertEquals(15, session.used)
        assertEquals(100, session.limit)
        assertEquals(15.0, session.usagePercent)
        assertEquals(18_000_000, session.periodDurationMs)
        val total = assertNotNull(quota.totalUsage)
        assertEquals(26, total.used)
    }

    @Test
    fun parseQuotaReportsInvalidPayload() {
        val body = "not json"

        val exception = assertFailsWith<KimiQuotaException> {
            KimiQuotaClient.parseQuota(body)
        }

        assertEquals("Could not parse usage data.", exception.message)
        assertEquals(body, exception.rawBody)
    }
}
