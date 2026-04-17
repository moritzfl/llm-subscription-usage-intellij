package de.moritzf.quota.idea

import de.moritzf.quota.OpenAiCodexQuota
import de.moritzf.quota.UsageWindow
import kotlin.test.Test
import kotlin.test.assertEquals

class QuotaIndicatorComponentTest {
    @Test
    fun indicatorBarDisplayTextFallsBackToSecondaryCodexWindow() {
        val quota = OpenAiCodexQuota(
            secondary = UsageWindow(usedPercent = 42.0),
        )

        assertEquals("42%", indicatorBarDisplayText(quota, error = null, loggedIn = true))
        assertEquals(42, indicatorDisplayPercent(quota, error = null, loggedIn = true))
        assertEquals("OpenAI usage quota: 42% used", buildQuotaTooltipText(quota, error = null, loggedIn = true))
    }

    @Test
    fun indicatorUsesReviewQuotaWhenNoCodexQuotaExists() {
        val quota = OpenAiCodexQuota(
            reviewPrimary = UsageWindow(usedPercent = 17.0),
        )

        assertEquals("Review 17%", indicatorBarDisplayText(quota, error = null, loggedIn = true))
        assertEquals(17, indicatorDisplayPercent(quota, error = null, loggedIn = true))
        assertEquals("OpenAI code review quota: 17% used", buildQuotaTooltipText(quota, error = null, loggedIn = true))
    }

    @Test
    fun indicatorFallsBackToReviewWindowBeforeNonBlockingCodexStateOnlyPayload() {
        val quota = OpenAiCodexQuota(
            allowed = true,
            limitReached = false,
            reviewPrimary = UsageWindow(usedPercent = 17.0),
        )

        assertEquals("Review 17%", indicatorBarDisplayText(quota, error = null, loggedIn = true))
        assertEquals(17, indicatorDisplayPercent(quota, error = null, loggedIn = true))
        assertEquals("OpenAI code review quota: 17% used", buildQuotaTooltipText(quota, error = null, loggedIn = true))
    }

    @Test
    fun indicatorShowsStateOnlyNotAllowedWithoutPretendingToLoad() {
        val quota = OpenAiCodexQuota(
            allowed = false,
        )

        assertEquals("OpenAI: not allowed", indicatorBarDisplayText(quota, error = null, loggedIn = true))
        assertEquals(-1, indicatorDisplayPercent(quota, error = null, loggedIn = true))
        assertEquals("OpenAI usage quota: usage not allowed", buildQuotaTooltipText(quota, error = null, loggedIn = true))
    }
}
