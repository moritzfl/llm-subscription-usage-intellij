package de.moritzf.quota.github

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubQuotaClientTest {
    @Test
    fun parseQuotaUsesAiCreditsAndPreciseResetTimestamp() {
        // Current microsoft/vscode parseQuotas contract: credit usage and UTC reset with time.
        val quota =
            GitHubQuotaClient.parseQuota(
                """{
              "copilot_plan":"pro", "token_based_billing":true,
              "quota_reset_date":"2026-11-01", "quota_reset_date_utc":"2026-11-01T09:30:00Z",
              "quota_snapshots":{"premium_interactions":{
                "entitlement":"3000", "credits_used":300,
                "remaining":0, "percent_remaining":90, "unlimited":false
              }}
            }"""
            )

        val premium = assertNotNull(quota.premiumInteractions)
        assertEquals("AI credits", premium.label)
        assertEquals(300.0, premium.used)
        assertEquals(10.0, premium.usagePercent)
        assertEquals("2026-11-01T09:30:00Z", premium.resetsAt.toString())
    }

    @Test
    fun parseQuotaUsesCreditPercentageWhenConsumedCreditsAreOmitted() {
        val quota =
            GitHubQuotaClient.parseQuota(
                """{"token_based_billing":true,"quota_snapshots":{"premium_interactions":{
              "entitlement":3000,"remaining":0,"percent_remaining":90
            }}}"""
            )

        val premium = assertNotNull(quota.premiumInteractions)
        assertEquals(300.0, premium.used)
        assertEquals(10.0, premium.usagePercent)
    }

    @Test
    fun parseQuotaUsesSnapshotCreditFlagAndResetBeforeRootFallback() {
        val quota =
            GitHubQuotaClient.parseQuota(
                """{
              "quota_reset_date":"2026-11-01", "quota_reset_date_utc":"2026-11-01T09:30:00Z",
              "quota_snapshots":{"premium_interactions":{
                "token_based_billing":true, "entitlement":1000,
                "credits_used":"250", "quota_reset_at":1792483200
              }}
            }"""
            )

        val premium = assertNotNull(quota.premiumInteractions)
        assertEquals("AI credits", premium.label)
        assertEquals(25.0, premium.usagePercent)
        assertEquals("2026-10-20T08:00:00Z", premium.resetsAt.toString())
    }

    @Test
    fun parseQuotaSkipsUnallocatedCreditPoolAndFallsBackFromInvalidUtcReset() {
        val quota =
            GitHubQuotaClient.parseQuota(
                """{
              "token_based_billing":true, "quota_reset_date":"2026-11-01",
              "quota_reset_date_utc":"unavailable",
              "quota_snapshots":{
                "premium_interactions":{"entitlement":0,"percent_remaining":0},
                "chat":{"entitlement":100,"percent_remaining":75}
              }
            }"""
            )

        assertNull(quota.premiumInteractions)
        assertEquals("2026-11-01T00:00:00Z", assertNotNull(quota.chat).resetsAt.toString())
    }

    @Test
    fun parseQuotaExtractsPaidPlanSnapshots() {
        val body =
            """
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
            """
                .trimIndent()

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
        val body =
            """
            {
              "copilot_plan": "pro",
              "quota_reset_date": "2026-07-01",
              "quota_snapshots": {
                "premium_interactions": {"entitlement": 1500, "percent_remaining": 40.0}
              }
            }
            """
                .trimIndent()

        val quota = GitHubQuotaClient.parseQuota(body)

        val premium = assertNotNull(quota.premiumInteractions)
        assertEquals(900.0, premium.used, 0.001)
        assertEquals(60.0, premium.usagePercent, 0.001)
        assertEquals("Copilot Pro", quota.plan)
    }

    @Test
    fun parseQuotaExtractsFreeTierCounters() {
        val body =
            """
            {
              "copilot_plan": "free",
              "limited_user_reset_date": "2026-06-23",
              "limited_user_quotas": {"chat": 38, "completions": 1600},
              "monthly_quotas": {"chat": 50, "completions": 2000}
            }
            """
                .trimIndent()

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
    fun parseQuotaMarksSubscriptionEndedAccount() {
        val body =
            """
            {
              "access_type_sku": "subscription_ended",
              "chat_enabled": false,
              "cli_enabled": false,
              "copilot_plan": "individual"
            }
            """
                .trimIndent()

        val quota = GitHubQuotaClient.parseQuota(body)

        assertEquals("Copilot Individual", quota.plan)
        assertEquals(GitHubSubscriptionState.SUBSCRIPTION_ENDED, quota.subscriptionState)
        assertTrue(quota.hasUsageState())
        assertTrue(quota.limitedWindows().isEmpty())
    }

    @Test
    fun parseQuotaMarksDisabledAccountWithoutUsageAsInactive() {
        val body =
            """
            {
              "chat_enabled": false,
              "cli_enabled": false,
              "copilot_plan": "individual"
            }
            """
                .trimIndent()

        val quota = GitHubQuotaClient.parseQuota(body)

        assertEquals(GitHubSubscriptionState.NO_ACTIVE_SUBSCRIPTION, quota.subscriptionState)
    }

    @Test
    fun usageEndpointUsesEnterpriseApiHost() {
        assertEquals(
            "https://api.github.com/copilot_internal/user",
            GitHubQuotaClient.usageEndpoint(null).toString(),
        )
        assertEquals(
            "https://api.github.example.com/copilot_internal/user",
            GitHubQuotaClient.usageEndpoint("https://github.example.com/some/path").toString(),
        )
        assertEquals(
            "https://api.github.example.com/copilot_internal/user",
            GitHubQuotaClient.usageEndpoint("api.github.example.com").toString(),
        )
    }

    @Test
    fun parseQuotaReportsInvalidPayload() {
        val body = "not json"

        val exception = assertFailsWith<GitHubQuotaException> { GitHubQuotaClient.parseQuota(body) }

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
