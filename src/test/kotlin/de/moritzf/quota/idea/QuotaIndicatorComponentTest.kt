package de.moritzf.quota.idea

import de.moritzf.quota.idea.ui.indicator.QuotaIndicatorComponent
import de.moritzf.quota.idea.ui.indicator.QuotaIndicatorData
import de.moritzf.quota.idea.ui.indicator.openCodeBarDisplayText
import de.moritzf.quota.idea.ui.indicator.buildOpenCodeTooltipText
import de.moritzf.quota.idea.ui.indicator.indicatorBarDisplayText
import de.moritzf.quota.idea.ui.indicator.indicatorDisplayPercent
import de.moritzf.quota.idea.ui.indicator.indicatorQuotaState
import de.moritzf.quota.idea.ui.indicator.QuotaUsageColors
import de.moritzf.quota.idea.ui.indicator.buildQuotaTooltipText
import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.openai.UsageWindow
import de.moritzf.quota.opencode.OpenCodeUsageWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

        assertEquals("17%", indicatorBarDisplayText(quota, error = null, loggedIn = true))
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

        assertEquals("17%", indicatorBarDisplayText(quota, error = null, loggedIn = true))
        assertEquals(17, indicatorDisplayPercent(quota, error = null, loggedIn = true))
        assertEquals("OpenAI code review quota: 17% used", buildQuotaTooltipText(quota, error = null, loggedIn = true))
    }

    @Test
    fun indicatorShowsStateOnlyNotAllowedWithoutPretendingToLoad() {
        val quota = OpenAiCodexQuota(
            allowed = false,
        )

        assertEquals("not allowed", indicatorBarDisplayText(quota, error = null, loggedIn = true))
        assertEquals(-1, indicatorDisplayPercent(quota, error = null, loggedIn = true))
        assertEquals("OpenAI usage quota: usage not allowed", buildQuotaTooltipText(quota, error = null, loggedIn = true))
    }

    @Test
    fun openCodeBarDisplayTextShowsPercentAndReset() {
        val quota = OpenCodeQuota(
            rollingUsage = OpenCodeUsageWindow(usagePercent = 42, resetInSec = 3661),
        )

        val text = openCodeBarDisplayText(quota, error = null)
        assertEquals("42% \u2022 1h", text)
    }

    @Test
    fun openCodeBarDisplayTextShowsBalanceWhenAvailableRegardlessOfUseBalance() {
        val quota = OpenCodeQuota(
            rollingUsage = OpenCodeUsageWindow(usagePercent = 42, resetInSec = 3661),
            availableBalance = 1_234_567_890L,
            useBalance = false,
        )

        val text = openCodeBarDisplayText(quota, error = null)
        assertEquals("42% \u2022 $12.35 \u2022 1h", text)
    }

    @Test
    fun openCodeBarDisplayTextIncludesBalanceWhenUseBalanceIsTrue() {
        val quota = OpenCodeQuota(
            rollingUsage = OpenCodeUsageWindow(usagePercent = 42, resetInSec = 3661),
            availableBalance = 1_234_567_890L,
            useBalance = true,
        )

        val text = openCodeBarDisplayText(quota, error = null)
        assertEquals("42% \u2022 $12.35 \u2022 1h", text)
    }

    @Test
    fun openCodeBarDisplayTextShowsRateLimitedWithBalance() {
        val quota = OpenCodeQuota(
            rollingUsage = OpenCodeUsageWindow(usagePercent = 100, resetInSec = 0, status = "rate-limited"),
            availableBalance = 500_000_000L,
            useBalance = true,
        )

        val text = openCodeBarDisplayText(quota, error = null)
        assertEquals("rate limited \u2022 $5.00", text)
    }

    @Test
    fun openCodeBarDisplayTextShowsNoDataWithBalance() {
        val quota = OpenCodeQuota(
            availableBalance = 1_000_000_000L,
            useBalance = true,
        )

        val text = openCodeBarDisplayText(quota, error = null)
        assertEquals("no data \u2022 $10.00", text)
    }

    @Test
    fun openCodeBarDisplayTextReturnsErrorWhenErrorPresent() {
        val quota = OpenCodeQuota(
            rollingUsage = OpenCodeUsageWindow(usagePercent = 42),
            availableBalance = 1_000_000_000L,
        )

        val text = openCodeBarDisplayText(quota, error = "Network timeout")
        assertEquals("error", text)
    }
}
