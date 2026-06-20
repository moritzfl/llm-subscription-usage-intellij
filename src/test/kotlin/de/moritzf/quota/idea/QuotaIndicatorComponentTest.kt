package de.moritzf.quota.idea

import de.moritzf.quota.cursor.CursorPlanUsage
import de.moritzf.quota.cursor.CursorQuota
import de.moritzf.quota.cursor.CursorRequestUsage
import de.moritzf.quota.cursor.CursorSpendLimit
import de.moritzf.quota.github.GitHubQuota
import de.moritzf.quota.github.GitHubUsageWindow
import de.moritzf.quota.idea.ui.indicator.QuotaIndicatorComponent
import de.moritzf.quota.idea.ui.indicator.buildGitHubTooltipText
import de.moritzf.quota.idea.ui.indicator.openCodeBarDisplayText
import de.moritzf.quota.idea.ui.indicator.ollamaBarDisplayText
import de.moritzf.quota.idea.ui.indicator.buildOpenCodeTooltipText
import de.moritzf.quota.idea.ui.indicator.indicatorBarDisplayText
import de.moritzf.quota.idea.ui.indicator.indicatorDisplayPercent
import de.moritzf.quota.idea.ui.indicator.indicatorQuotaState
import de.moritzf.quota.idea.ui.indicator.QuotaUsageColors
import de.moritzf.quota.idea.ui.indicator.buildQuotaTooltipText
import de.moritzf.quota.ollama.OllamaQuota
import de.moritzf.quota.ollama.OllamaUsageWindow
import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.openai.OpenAiUsageResponseFixtures.businessMemberAssignedCreditsDepleted
import de.moritzf.quota.openai.OpenAiUsageResponseFixtures.businessMemberWithAssignedCredits
import de.moritzf.quota.openai.OpenAiUsageResponseFixtures.plusWithRateLimitsAndZeroPurchasedCredits
import de.moritzf.quota.openai.isCreditsDepleted
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.openai.UsageWindow
import de.moritzf.quota.opencode.OpenCodeUsageWindow
import kotlin.time.Clock
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

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
    fun indicatorUsesShortestOpenAiWindowNormally() {
        val quota = OpenAiCodexQuota(
            primary = UsageWindow(usedPercent = 25.0, windowDuration = Duration.ofDays(7)),
            secondary = UsageWindow(usedPercent = 42.0, windowDuration = Duration.ofHours(5)),
        )

        assertEquals("42%", indicatorBarDisplayText(quota, error = null, loggedIn = true))
        assertEquals(42, indicatorDisplayPercent(quota, error = null, loggedIn = true))
    }

    @Test
    fun indicatorUsesLatestResetWhenOpenAiLimitsAreExhausted() {
        val now = Clock.System.now()
        val quota = OpenAiCodexQuota(
            primary = UsageWindow(
                usedPercent = 100.0,
                windowDuration = Duration.ofHours(5),
                resetsAt = now.plus(5.seconds),
            ),
            secondary = UsageWindow(
                usedPercent = 100.0,
                windowDuration = Duration.ofDays(7),
                resetsAt = now.plus(190_000.seconds),
            ),
        )

        val text = indicatorBarDisplayText(quota, error = null, loggedIn = true)
        assertTrue(text.startsWith("100% • 2d"))
        assertEquals(100, indicatorDisplayPercent(quota, error = null, loggedIn = true))
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
    fun indicatorShowsAvailableForBusinessMemberWithAssignedCreditsFixture() {
        val quota = businessMemberWithAssignedCredits()

        assertEquals("available", indicatorBarDisplayText(quota, error = null, loggedIn = true))
        assertEquals(-1, indicatorDisplayPercent(quota, error = null, loggedIn = true))
        assertTrue(buildQuotaTooltipText(quota, error = null, loggedIn = true).contains("Assigned credits: Available"))
    }

    @Test
    fun indicatorShowsDepletedForBusinessMemberAssignedCreditsDepletedFixture() {
        val quota = businessMemberAssignedCreditsDepleted()
        val tooltip = buildQuotaTooltipText(quota, error = null, loggedIn = true)

        assertEquals("100%", indicatorBarDisplayText(quota, error = null, loggedIn = true))
        assertEquals(100, indicatorDisplayPercent(quota, error = null, loggedIn = true))
        assertTrue(tooltip.contains("Assigned credits:"))
        assertTrue(tooltip.lowercase().contains("depleted"))
    }

    @Test
    fun indicatorUsesRateLimitsForPlusFixtureWithZeroPurchasedCredits() {
        val quota = plusWithRateLimitsAndZeroPurchasedCredits()

        val text = indicatorBarDisplayText(quota, error = null, loggedIn = true)
        assertTrue(text.startsWith("1%"))
        assertEquals(1, indicatorDisplayPercent(quota, error = null, loggedIn = true))
        assertFalse(quota.isCreditsDepleted())
        assertFalse(buildQuotaTooltipText(quota, error = null, loggedIn = true).contains("Assigned credits"))
    }

    @Test
    fun indicatorShowsCreditsBalanceWhenPresent() {
        val quota = OpenAiCodexQuota(
            planType = "self_serve_business_usage_based",
            credits = de.moritzf.quota.openai.OpenAiCredits(hasCredits = true, balance = "15.0"),
        )

        assertEquals("$15.00", indicatorBarDisplayText(quota, error = null, loggedIn = true))
    }

    @Test
    fun indicatorShowsLoadingWhenNoWindowsAvailable() {
        val quota = OpenAiCodexQuota(
            allowed = false,
        )

        assertEquals("loading...", indicatorBarDisplayText(quota, error = null, loggedIn = true))
        assertEquals(-1, indicatorDisplayPercent(quota, error = null, loggedIn = true))
        assertEquals("OpenAI usage quota: loading", buildQuotaTooltipText(quota, error = null, loggedIn = true))
    }

    @Test
    fun githubTooltipHidesUnlimitedWindows() {
        val quota = GitHubQuota(
            plan = "Copilot Individual",
            premiumInteractions = GitHubUsageWindow(label = "Premium requests", usagePercent = 26.833),
            chat = GitHubUsageWindow(label = "Chat", unlimited = true),
            completions = GitHubUsageWindow(label = "Completions", unlimited = true),
        )

        val tooltip = buildGitHubTooltipText(quota, error = null)

        assertTrue(tooltip.contains("Premium requests: 27%"))
        assertFalse(tooltip.contains("Chat"))
        assertFalse(tooltip.contains("Completions"))
    }

    @Test
    fun cursorIndicatorPrefersIncludedUsageOverTeamSpend() {
        val quota = CursorQuota(
            planUsage = CursorPlanUsage(totalPercentUsed = 12.0),
            spendLimit = CursorSpendLimit(pooledLimitUsd = 100.0, pooledUsedUsd = 100.0),
        )

        assertEquals("12%", de.moritzf.quota.idea.ui.indicator.cursorBarDisplayText(quota, error = null))
        assertEquals(12, de.moritzf.quota.idea.ui.indicator.cursorIndicatorState(quota)?.percent)
        assertTrue(de.moritzf.quota.idea.ui.indicator.buildCursorTooltipText(quota, error = null).contains("Included: 12%"))
    }

    @Test
    fun cursorIndicatorUsesLegacyRequestUsageWhenPresent() {
        val quota = CursorQuota(
            planUsage = CursorPlanUsage(totalPercentUsed = 12.0),
            requestUsage = CursorRequestUsage(used = 150, limit = 500),
        )

        assertEquals("30%", de.moritzf.quota.idea.ui.indicator.cursorBarDisplayText(quota, error = null))
        assertEquals(30, de.moritzf.quota.idea.ui.indicator.cursorIndicatorState(quota)?.percent)
    }

    @Test
    fun openCodeBarDisplayTextShowsPercentAndReset() {
        val quota = OpenCodeQuota(
            rollingUsage = OpenCodeUsageWindow(usagePercent = 42, resetInSec = 3661),
        )

        val text = openCodeBarDisplayText(quota, error = null)
        assertEquals("42% \u2022 1h 1m", text)
    }

    @Test
    fun openCodeBarDisplayTextUsesLongestExhaustedReset() {
        val quota = OpenCodeQuota(
            rollingUsage = OpenCodeUsageWindow(usagePercent = 100, resetInSec = 5 * 60 * 60),
            weeklyUsage = OpenCodeUsageWindow(usagePercent = 100, resetInSec = 2 * 24 * 60 * 60 + 4 * 60 * 60),
        )

        assertEquals("100% • 2d 4h", openCodeBarDisplayText(quota, error = null))
    }

    @Test
    fun ollamaBarDisplayTextUsesShortestWindowNormally() {
        val now = Clock.System.now()
        val quota = OllamaQuota(
            sessionUsage = OllamaUsageWindow(usagePercent = 12.0, resetsAt = now.plus(3600.seconds)),
            weeklyUsage = OllamaUsageWindow(usagePercent = 44.0, resetsAt = now.plus((7 * 24 * 3600).seconds)),
        )

        val text = ollamaBarDisplayText(quota, error = null)
        assertTrue(text.startsWith("12% •"))
    }

    @Test
    fun openCodeBarDisplayTextShowsRateLimited() {
        val quota = OpenCodeQuota(
            rollingUsage = OpenCodeUsageWindow(usagePercent = 100, resetInSec = 3600, status = "rate-limited"),
        )

        val text = openCodeBarDisplayText(quota, error = null)
        assertEquals("100% \u2022 1h", text)
    }

    @Test
    fun openCodeBarDisplayTextShowsBalanceWithoutUsageWindows() {
        val quota = OpenCodeQuota(
            availableBalance = 1_000_000_000L,
            useBalance = true,
        )

        val text = openCodeBarDisplayText(quota, error = null)
        assertEquals("\$10.00", text)
        assertEquals("OpenCode Zen credits: Balance: \$10.00", buildOpenCodeTooltipText(quota, error = null))
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
