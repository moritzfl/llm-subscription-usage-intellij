package de.moritzf.quota.openai

import de.moritzf.quota.idea.ui.popup.getLimitWarning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAiCreditsQuotaTest {
    @Test
    fun businessMemberWithAssignedCreditsIsAvailable() {
        val quota = OpenAiUsageResponseFixtures.businessMemberWithAssignedCredits()

        assertEquals("self_serve_business_usage_based", quota.planType)
        assertEquals(OpenAiUsageResponseFixtures.WORKSPACE_ACCOUNT_ID, quota.accountId)
        assertEquals("member.with.credits@example.com", quota.email)
        assertTrue(quota.isAssignedCreditsQuota())
        assertFalse(quota.isCreditsDepleted())
        assertNull(getLimitWarning(quota))
        assertTrue(quota.hasUsageState())
    }

    @Test
    fun businessMemberWithDepletedAssignedCreditsIsBlocked() {
        val quota = OpenAiUsageResponseFixtures.businessMemberAssignedCreditsDepleted()

        assertEquals("member.without.credits@example.com", quota.email)
        assertTrue(quota.isAssignedCreditsQuota())
        assertTrue(quota.isCreditsDepleted())
        assertEquals("workspace_member_credits_depleted", quota.rateLimitReachedType)
        assertEquals("Assigned credits depleted", getLimitWarning(quota))
    }

    @Test
    fun plusPayloadWithZeroPurchasedCreditsIsNotAssignedQuota() {
        val quota = OpenAiUsageResponseFixtures.plusWithRateLimitsAndZeroPurchasedCredits()

        assertEquals("plus", quota.planType)
        assertEquals("user@example.com", quota.email)
        assertFalse(quota.isAssignedCreditsQuota())
        assertFalse(quota.isCreditsDepleted())
        assertNull(getLimitWarning(quota))
        assertEquals(listOf(0, 0), quota.credits?.approxLocalMessages)
        assertEquals(listOf(0, 0), quota.credits?.approxCloudMessages)
    }

    @Test
    fun freePayloadWithWeeklyLimitIsNotAssignedQuota() {
        val quota = OpenAiUsageResponseFixtures.freeWithWeeklyRateLimit()

        assertEquals("free", quota.planType)
        assertFalse(quota.isAssignedCreditsQuota())
        assertFalse(quota.isCreditsDepleted())
        assertNull(getLimitWarning(quota))
    }

    @Test
    fun prolitePayloadWithAdditionalLimitsIsNotAssignedQuota() {
        val quota = OpenAiUsageResponseFixtures.proliteWithAdditionalRateLimits()

        assertEquals("prolite", quota.planType)
        assertFalse(quota.isAssignedCreditsQuota())
        assertFalse(quota.isCreditsDepleted())
        assertNull(getLimitWarning(quota))
    }

    @Test
    fun businessWorkspaceOwnerCreditsDepletedFormatDeserializes() {
        val quota = OpenAiUsageResponseFixtures.businessOwnerCreditsDepleted()

        assertEquals("workspace_owner_credits_depleted", quota.rateLimitReachedType)
        assertTrue(quota.isAssignedCreditsQuota())
        assertTrue(quota.isCreditsDepleted())
        assertEquals("Credits depleted", getLimitWarning(quota))
    }

    @Test
    fun businessWorkspaceOwnerUsageLimitFormatDeserializes() {
        val quota = OpenAiUsageResponseFixtures.businessOwnerUsageLimitReached()

        assertEquals("workspace_owner_usage_limit_reached", quota.rateLimitReachedType)
        assertTrue(quota.isAssignedCreditsQuota())
        assertTrue(quota.isCreditsDepleted())
        assertEquals("Credits depleted", getLimitWarning(quota))
    }

    @Test
    fun businessWorkspaceMemberUsageLimitFormatDeserializes() {
        val quota = OpenAiUsageResponseFixtures.businessMemberUsageLimitReached()

        assertEquals("workspace_member_usage_limit_reached", quota.rateLimitReachedType)
        assertTrue(quota.isAssignedCreditsQuota())
        assertTrue(quota.isCreditsDepleted())
        assertEquals("Credits depleted", getLimitWarning(quota))
    }

    @Test
    fun businessMemberWithAssignedCreditsAndBalanceIsAvailable() {
        val quota = OpenAiUsageResponseFixtures.businessMemberWithAssignedCreditsAndBalance()

        assertTrue(quota.isAssignedCreditsQuota())
        assertFalse(quota.isCreditsDepleted())
        assertNull(getLimitWarning(quota))
        assertEquals("125.50", quota.credits?.balance)
        assertEquals("1-5", formatApproxMessages(quota.credits?.approxCloudMessages))
    }

    @Test
    fun businessMemberWithUnlimitedCreditsIsNotDepleted() {
        val quota = OpenAiUsageResponseFixtures.businessMemberWithUnlimitedCredits()

        assertTrue(quota.isAssignedCreditsQuota())
        assertFalse(quota.isCreditsDepleted())
        assertNull(getLimitWarning(quota))
    }

    @Test
    fun businessMemberWithIndividualSpendLimitReportsWarning() {
        val quota = OpenAiUsageResponseFixtures.businessMemberIndividualSpendLimitReached()

        assertTrue(quota.isAssignedCreditsQuota())
        assertTrue(quota.isCreditsDepleted())
        assertEquals(50.0, quota.spendControl?.individualLimit ?: 0.0, 0.0)
        assertEquals("Individual spend limit reached", getLimitWarning(quota))
    }

    @Test
    fun businessOwnerWithOverageLimitReportsDepleted() {
        val quota = OpenAiUsageResponseFixtures.businessOwnerOverageLimitReached()

        assertTrue(quota.isAssignedCreditsQuota())
        assertTrue(quota.isCreditsDepleted())
        assertEquals("Credits depleted", getLimitWarning(quota))
    }

    @Test
    fun businessMemberPayloadWithoutOptionalFieldsStillDeserializes() {
        val quota = OpenAiUsageResponseFixtures.businessMemberOmittingOptionalFields()

        assertEquals("self_serve_business_usage_based", quota.planType)
        assertTrue(quota.isAssignedCreditsQuota())
        assertFalse(quota.isCreditsDepleted())
        assertNull(getLimitWarning(quota))
    }

    @Test
    fun formatApproxMessagesUsesSingleValueOrRange() {
        assertEquals("3", formatApproxMessages(listOf(3)))
        assertEquals("0", formatApproxMessages(listOf(0, 0)))
        assertEquals("1-5", formatApproxMessages(listOf(1, 5)))
        assertNull(formatApproxMessages(null))
    }
}
