package de.moritzf.quota.idea

import de.moritzf.quota.cursor.CursorPlanUsage
import de.moritzf.quota.cursor.CursorQuota
import de.moritzf.quota.cursor.CursorRequestUsage
import de.moritzf.quota.cursor.CursorSpendLimit
import de.moritzf.quota.github.GitHubQuota
import de.moritzf.quota.github.GitHubSubscriptionState
import de.moritzf.quota.github.GitHubUsageWindow
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.ui.indicator.ProviderAuthState
import de.moritzf.quota.idea.ui.indicator.buildIndicatorTooltip
import de.moritzf.quota.idea.ui.indicator.gitHubBarDisplayText
import de.moritzf.quota.idea.ui.indicator.indicatorBarDisplayText
import de.moritzf.quota.idea.ui.indicator.indicatorDisplayPercent
import de.moritzf.quota.idea.ui.indicator.ollamaBarDisplayText
import de.moritzf.quota.idea.ui.indicator.openCodeBarDisplayText
import de.moritzf.quota.ollama.OllamaQuota
import de.moritzf.quota.ollama.OllamaUsageWindow
import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.openai.OpenAiUsageResponseFixtures.businessMemberAssignedCreditsDepleted
import de.moritzf.quota.openai.OpenAiUsageResponseFixtures.businessMemberWithAssignedCredits
import de.moritzf.quota.openai.OpenAiUsageResponseFixtures.plusWithRateLimitsAndZeroPurchasedCredits
import de.moritzf.quota.openai.UsageWindow
import de.moritzf.quota.openai.isCreditsDepleted
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.opencode.OpenCodeUsageWindow
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class QuotaIndicatorComponentTest {
    @Test
    fun indicatorBarDisplayTextFallsBackToSecondaryCodexWindow() {
        val quota =
            OpenAiCodexQuota(
                secondary = UsageWindow(usedPercent = 42.0, windowDuration = Duration.ofDays(7))
            )

        assertEquals("42%", indicatorBarDisplayText(quota, error = null, loggedIn = true))
        assertEquals(42, indicatorDisplayPercent(quota, error = null, loggedIn = true))
        assertEquals("OpenAI • 42% Weekly", tooltip(QuotaProviderType.OPEN_AI, quota))
    }

    @Test
    fun indicatorUsesReviewQuotaWhenNoCodexQuotaExists() {
        val quota = OpenAiCodexQuota(reviewPrimary = UsageWindow(usedPercent = 17.0))

        assertEquals("17%", indicatorBarDisplayText(quota, error = null, loggedIn = true))
        assertEquals(17, indicatorDisplayPercent(quota, error = null, loggedIn = true))
        assertEquals("OpenAI • 17% Review", tooltip(QuotaProviderType.OPEN_AI, quota))
    }

    @Test
    fun indicatorUsesShortestOpenAiWindowNormally() {
        val quota =
            OpenAiCodexQuota(
                primary = UsageWindow(usedPercent = 25.0, windowDuration = Duration.ofDays(7)),
                secondary = UsageWindow(usedPercent = 42.0, windowDuration = Duration.ofHours(5)),
            )

        assertEquals("42%", indicatorBarDisplayText(quota, error = null, loggedIn = true))
        assertEquals(42, indicatorDisplayPercent(quota, error = null, loggedIn = true))
    }

    @Test
    fun indicatorUsesLatestResetWhenOpenAiLimitsAreExhausted() {
        val now = Clock.System.now()
        val quota =
            OpenAiCodexQuota(
                primary =
                    UsageWindow(
                        usedPercent = 100.0,
                        windowDuration = Duration.ofHours(5),
                        resetsAt = now.plus(5.seconds),
                    ),
                secondary =
                    UsageWindow(
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
        val quota =
            OpenAiCodexQuota(
                allowed = true,
                limitReached = false,
                reviewPrimary = UsageWindow(usedPercent = 17.0),
            )

        assertEquals("17%", indicatorBarDisplayText(quota, error = null, loggedIn = true))
        assertEquals(17, indicatorDisplayPercent(quota, error = null, loggedIn = true))
        assertEquals("OpenAI • 17% Review", tooltip(QuotaProviderType.OPEN_AI, quota))
    }

    @Test
    fun indicatorShowsAvailableForBusinessMemberWithAssignedCreditsFixture() {
        val quota = businessMemberWithAssignedCredits()

        assertEquals("available", indicatorBarDisplayText(quota, error = null, loggedIn = true))
        assertEquals(-1, indicatorDisplayPercent(quota, error = null, loggedIn = true))
        assertEquals("OpenAI • Business Usage Based", tooltip(QuotaProviderType.OPEN_AI, quota))
    }

    @Test
    fun indicatorShowsDepletedForBusinessMemberAssignedCreditsDepletedFixture() {
        val quota = businessMemberAssignedCreditsDepleted()
        assertEquals("100%", indicatorBarDisplayText(quota, error = null, loggedIn = true))
        assertEquals(100, indicatorDisplayPercent(quota, error = null, loggedIn = true))
        assertEquals(
            "OpenAI • Business Usage Based • 100%",
            tooltip(QuotaProviderType.OPEN_AI, quota),
        )
    }

    @Test
    fun indicatorUsesRateLimitsForPlusFixtureWithZeroPurchasedCredits() {
        val quota = plusWithRateLimitsAndZeroPurchasedCredits()

        val text = indicatorBarDisplayText(quota, error = null, loggedIn = true)
        assertTrue(text.startsWith("1%"))
        assertEquals(1, indicatorDisplayPercent(quota, error = null, loggedIn = true))
        assertFalse(quota.isCreditsDepleted())
        val tooltip = tooltip(QuotaProviderType.OPEN_AI, quota)
        assertTrue(tooltip.startsWith("OpenAI • Plus • 1%"))
        assertFalse(tooltip.contains("Assigned credits"))
    }

    @Test
    fun indicatorShowsCreditsBalanceWhenPresent() {
        val quota =
            OpenAiCodexQuota(
                planType = "self_serve_business_usage_based",
                credits =
                    de.moritzf.quota.openai.OpenAiCredits(hasCredits = true, balance = "15.0"),
            )

        assertEquals("$15.00", indicatorBarDisplayText(quota, error = null, loggedIn = true))
    }

    @Test
    fun indicatorShowsLoadingWhenNoWindowsAvailable() {
        val quota = OpenAiCodexQuota(allowed = false)

        assertEquals("loading...", indicatorBarDisplayText(quota, error = null, loggedIn = true))
        assertEquals(-1, indicatorDisplayPercent(quota, error = null, loggedIn = true))
        assertEquals("OpenAI", tooltip(QuotaProviderType.OPEN_AI, quota))
    }

    @Test
    fun githubTooltipHidesUnlimitedWindows() {
        val quota =
            GitHubQuota(
                plan = "Copilot Individual",
                premiumInteractions =
                    GitHubUsageWindow(label = "Premium requests", usagePercent = 26.833),
                chat = GitHubUsageWindow(label = "Chat", unlimited = true),
                completions = GitHubUsageWindow(label = "Completions", unlimited = true),
            )

        val tooltip = tooltip(QuotaProviderType.GITHUB, quota)

        assertEquals("GitHub Copilot • Copilot Individual • 27% Premium requests", tooltip)
        assertFalse(tooltip.contains("Chat"))
        assertFalse(tooltip.contains("Completions"))
    }

    @Test
    fun githubIndicatorShowsEndedSubscriptionState() {
        val quota =
            GitHubQuota(
                plan = "Copilot Individual",
                subscriptionState = GitHubSubscriptionState.SUBSCRIPTION_ENDED,
            )

        assertEquals("ended", gitHubBarDisplayText(quota, error = null))
        assertEquals(
            "GitHub Copilot • Copilot Individual",
            tooltip(QuotaProviderType.GITHUB, quota),
        )
    }

    @Test
    fun githubIndicatorShowsInactiveSubscriptionState() {
        val quota =
            GitHubQuota(
                plan = "Copilot Individual",
                subscriptionState = GitHubSubscriptionState.NO_ACTIVE_SUBSCRIPTION,
            )

        assertEquals("inactive", gitHubBarDisplayText(quota, error = null))
        assertEquals(
            "GitHub Copilot • Copilot Individual",
            tooltip(QuotaProviderType.GITHUB, quota),
        )
    }

    @Test
    fun cursorIndicatorPrefersIncludedUsageOverTeamSpend() {
        val quota =
            CursorQuota(
                planUsage = CursorPlanUsage(totalPercentUsed = 12.0),
                spendLimit = CursorSpendLimit(pooledLimitUsd = 100.0, pooledUsedUsd = 100.0),
            )

        assertEquals(
            "12%",
            de.moritzf.quota.idea.ui.indicator.cursorBarDisplayText(quota, error = null),
        )
        assertEquals(12, de.moritzf.quota.idea.ui.indicator.cursorIndicatorState(quota)?.percent)
        assertEquals("Cursor • 12% Included", tooltip(QuotaProviderType.CURSOR, quota))
    }

    @Test
    fun cursorIndicatorUsesLegacyRequestUsageWhenPresent() {
        val quota =
            CursorQuota(
                planUsage = CursorPlanUsage(totalPercentUsed = 12.0),
                requestUsage = CursorRequestUsage(used = 150, limit = 500),
            )

        assertEquals(
            "30%",
            de.moritzf.quota.idea.ui.indicator.cursorBarDisplayText(quota, error = null),
        )
        assertEquals(30, de.moritzf.quota.idea.ui.indicator.cursorIndicatorState(quota)?.percent)
    }

    @Test
    fun openCodeBarDisplayTextShowsPercentAndReset() {
        val quota =
            OpenCodeQuota(
                rollingUsage = OpenCodeUsageWindow(usagePercent = 42.0, resetInSec = 3661)
            )

        val text = openCodeBarDisplayText(quota, error = null)
        assertEquals("42% \u2022 1h 1m", text)
    }

    @Test
    fun openCodeBarDisplayTextUsesLongestExhaustedReset() {
        val quota =
            OpenCodeQuota(
                rollingUsage = OpenCodeUsageWindow(usagePercent = 100.0, resetInSec = 5 * 60 * 60),
                weeklyUsage =
                    OpenCodeUsageWindow(
                        usagePercent = 100.0,
                        resetInSec = 2 * 24 * 60 * 60 + 4 * 60 * 60,
                    ),
            )

        assertEquals("100% • 2d 4h", openCodeBarDisplayText(quota, error = null))
    }

    @Test
    fun ollamaBarDisplayTextUsesShortestWindowNormally() {
        val now = Clock.System.now()
        val quota =
            OllamaQuota(
                sessionUsage =
                    OllamaUsageWindow(usagePercent = 12.0, resetsAt = now.plus(3600.seconds)),
                weeklyUsage =
                    OllamaUsageWindow(
                        usagePercent = 44.0,
                        resetsAt = now.plus((7 * 24 * 3600).seconds),
                    ),
            )

        val text = ollamaBarDisplayText(quota, error = null)
        assertTrue(text.startsWith("12% •"))
    }

    @Test
    fun ollamaBarDisplayTextFallsBackToKnownWindowWhenResetMissing() {
        val quota =
            OllamaQuota(
                sessionUsage = OllamaUsageWindow(usagePercent = 8.0),
                weeklyUsage = OllamaUsageWindow(usagePercent = 5.7),
            )

        assertEquals("8% (5h)", ollamaBarDisplayText(quota, error = null))
        assertEquals("Ollama • 8% Session", tooltip(QuotaProviderType.OLLAMA, quota))
    }

    @Test
    fun ollamaBarDisplayTextUsesMonthlyCreditPlan() {
        val quota = OllamaQuota(monthlyUsage = OllamaUsageWindow(usagePercent = 0.0))

        assertEquals("0% (30d)", ollamaBarDisplayText(quota, error = null))
        assertEquals("Ollama • 0% Monthly", tooltip(QuotaProviderType.OLLAMA, quota))
    }

    @Test
    fun ollamaBarDisplayTextUsesMonthlyResetCountdown() {
        val now = Clock.System.now()
        val quota =
            OllamaQuota(
                monthlyUsage =
                    OllamaUsageWindow(
                        usagePercent = 12.0,
                        periodStartedAt = now.minus((24 * 24 * 3600).seconds),
                        resetsAt = now.plus((7 * 24 * 3600).seconds),
                    )
            )

        val text = ollamaBarDisplayText(quota, error = null)
        assertTrue(text.startsWith("12% •"))
        val tooltip = tooltip(QuotaProviderType.OLLAMA, quota)
        assertTrue(tooltip.startsWith("Ollama • 12% Monthly • Resets in "))
    }

    @Test
    fun ollamaTooltipPrefersResetCountdownOverWindowFallback() {
        val now = Clock.System.now()
        val quota =
            OllamaQuota(
                sessionUsage =
                    OllamaUsageWindow(usagePercent = 8.0, resetsAt = now.plus(3600.seconds)),
                weeklyUsage = OllamaUsageWindow(usagePercent = 5.7),
            )

        val tooltip = tooltip(QuotaProviderType.OLLAMA, quota)
        assertTrue(tooltip.startsWith("Ollama • 8% Session • Resets in "))
        assertFalse(tooltip.contains("unknown"))
        assertFalse(tooltip.contains("(7d)"))
    }

    @Test
    fun openCodeBarDisplayTextShowsRateLimited() {
        val quota =
            OpenCodeQuota(
                rollingUsage =
                    OpenCodeUsageWindow(
                        usagePercent = 100.0,
                        resetInSec = 3600,
                        status = "rate-limited",
                    )
            )

        val text = openCodeBarDisplayText(quota, error = null)
        assertEquals("100% \u2022 1h", text)
    }

    @Test
    fun openCodeBarDisplayTextShowsBalanceWithoutUsageWindows() {
        val quota =
            OpenCodeQuota(
                availableBalance = 1_000_000_000L,
                useBalance = true,
            )

        val text = openCodeBarDisplayText(quota, error = null)
        assertEquals("\$10.00", text)
        assertEquals("OpenCode", tooltip(QuotaProviderType.OPEN_CODE, quota))
    }

    @Test
    fun openCodeBarDisplayTextReturnsErrorWhenErrorPresent() {
        val quota =
            OpenCodeQuota(
                rollingUsage = OpenCodeUsageWindow(usagePercent = 42.0),
                availableBalance = 1_000_000_000L,
            )

        val text = openCodeBarDisplayText(quota, error = "Network timeout")
        assertEquals("error", text)
    }
}

private fun tooltip(
    type: QuotaProviderType,
    quota: de.moritzf.quota.shared.ProviderQuota?,
    error: String? = null,
    authState: ProviderAuthState = ProviderAuthState.AUTHENTICATED,
): String = buildIndicatorTooltip(type, quota, error, authState)
