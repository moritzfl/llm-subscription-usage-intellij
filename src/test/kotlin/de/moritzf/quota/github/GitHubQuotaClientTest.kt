package de.moritzf.quota.github

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubQuotaClientTest {
    @Test
    fun parseQuotaExtractsPaidPlanSnapshots() {
        val body = """
            {
              "copilot_plan": "individual",
              "quota_reset_date": "2026-07-01",
              "quota_snapshots": {
                "premium_interactions": {
                  "entitlement": 300,
                  "remaining": 219.5,
                  "percent_remaining": 73.17,
                  "unlimited": false
                },
                "chat": {"unlimited": true},
                "completions": {"unlimited": true}
              }
            }
        """.trimIndent()

        val quota = GitHubQuotaClient.parseQuota(body)

        assertEquals("Copilot Individual", quota.plan)
        val premium = assertNotNull(quota.premiumInteractions)
        assertEquals("Premium requests", premium.label)
        assertEquals(80.5, premium.used, 0.001)
        assertEquals(300.0, premium.limit)
        assertEquals(26.833, premium.usagePercent, 0.001)
        assertEquals("2026-07-01T00:00:00Z", premium.resetsAt.toString())
        val chat = assertNotNull(quota.chat)
        assertTrue(chat.unlimited)
        assertEquals(listOf(premium), quota.limitedWindows())
    }

    @Test
    fun parseQuotaComputesRemainingFromPercentWhenAbsoluteValueMissing() {
        val body = """
            {
              "copilot_plan": "pro",
              "quota_reset_date": "2026-07-01",
              "quota_snapshots": {
                "premium_interactions": {"entitlement": 1500, "percent_remaining": 40.0}
              }
            }
        """.trimIndent()

        val quota = GitHubQuotaClient.parseQuota(body)

        val premium = assertNotNull(quota.premiumInteractions)
        assertEquals(900.0, premium.used, 0.001)
        assertEquals(60.0, premium.usagePercent, 0.001)
        assertEquals("Copilot Pro", quota.plan)
    }

    @Test
    fun parseQuotaExtractsFreeTierCounters() {
        val body = """
            {
              "copilot_plan": "free",
              "limited_user_reset_date": "2026-06-23",
              "limited_user_quotas": {"chat": 38, "completions": 1600},
              "monthly_quotas": {"chat": 50, "completions": 2000}
            }
        """.trimIndent()

        val quota = GitHubQuotaClient.parseQuota(body)

        assertEquals("Copilot Free", quota.plan)
        assertNull(quota.premiumInteractions)
        val chat = assertNotNull(quota.chat)
        assertEquals(12.0, chat.used)
        assertEquals(50.0, chat.limit)
        assertEquals(24.0, chat.usagePercent, 0.001)
        assertEquals("2026-06-23T00:00:00Z", chat.resetsAt.toString())
        val completions = assertNotNull(quota.completions)
        assertEquals(400.0, completions.used)
        assertEquals(20.0, completions.usagePercent, 0.001)
    }

    @Test
    fun parseQuotaReportsInvalidPayload() {
        val body = "not json"

        val exception = assertFailsWith<GitHubQuotaException> {
            GitHubQuotaClient.parseQuota(body)
        }

        assertEquals("Could not parse usage data.", exception.message)
        assertEquals(body, exception.rawBody)
    }

    private fun assertEquals(expected: Double, actual: Double, tolerance: Double) {
        assertTrue(
            kotlin.math.abs(expected - actual) <= tolerance,
            "expected $expected within $tolerance of $actual",
        )
    }
}
