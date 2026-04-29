package de.moritzf.quota.minimax

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

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
    fun parseQuotaReportsApiError() {
        val body = """{"base_resp":{"status_code":1001,"status_msg":"no plan"},"model_remains":[]}"""

        val exception = assertFailsWith<MiniMaxQuotaException> {
            MiniMaxQuotaClient.parseQuota(body, MiniMaxRegion.CN)
        }

        assertEquals("MiniMax API error: no plan", exception.message)
        assertEquals(body, exception.rawBody)
    }
}
