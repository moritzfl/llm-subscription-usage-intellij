package de.moritzf.quota.idea

import de.moritzf.quota.claude.ClaudeQuota
import de.moritzf.quota.claude.ClaudeUsageWindow
import de.moritzf.quota.cursor.CursorQuota
import de.moritzf.quota.github.GitHubQuota
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.ui.indicator.ProviderAuthState
import de.moritzf.quota.idea.ui.indicator.buildIndicatorTooltip
import de.moritzf.quota.idea.ui.indicator.formatIndicatorTooltip
import de.moritzf.quota.idea.ui.indicator.indicatorPlanName
import de.moritzf.quota.kimi.KimiQuota
import de.moritzf.quota.minimax.MiniMaxQuota
import de.moritzf.quota.minimax.MiniMaxUsageWindow
import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.supergrok.SuperGrokQuota
import de.moritzf.quota.zai.ZaiQuota
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

class QuotaIndicatorTooltipTest {
    @Test
    fun formatJoinsProviderPlanPercentAndReset() {
        assertEquals(
            "OpenAI • Plus • 42% Weekly • Resets in 1h",
            formatIndicatorTooltip(provider = "OpenAI", plan = "Plus", percent = 42, windowKind = "Weekly", reset = "Resets in 1h"),
        )
    }

    @Test
    fun formatOmitsBlankPlanAndPlanMatchingProvider() {
        assertEquals("Ollama • 8%", formatIndicatorTooltip(provider = "Ollama", plan = "  ", percent = 8))
        assertEquals("Cursor • 12%", formatIndicatorTooltip(provider = "Cursor", plan = "Cursor", percent = 12))
        assertEquals("Z.ai • 10%", formatIndicatorTooltip(provider = "Z.ai", plan = "z.ai", percent = 10))
    }

    @Test
    fun formatUsesStatusOnlyWhenUsageMissing() {
        assertEquals("OpenAI • loading", formatIndicatorTooltip(provider = "OpenAI", status = "loading"))
        assertEquals("OpenAI • timeout", formatIndicatorTooltip(provider = "OpenAI", status = "timeout"))
        assertEquals("OpenAI • Plus", formatIndicatorTooltip(provider = "OpenAI", plan = "Plus", status = "loading"))
        assertEquals("OpenAI • 5%", formatIndicatorTooltip(provider = "OpenAI", percent = 5, status = "loading"))
    }

    @Test
    fun buildShowsLoadingErrorAndLoggedOutWhenNoUsage() {
        assertEquals(
            "OpenAI • loading",
            buildIndicatorTooltip(QuotaProviderType.OPEN_AI, quota = null, error = null, ProviderAuthState.AUTHENTICATED),
        )
        assertEquals(
            "OpenAI • Network timeout",
            buildIndicatorTooltip(QuotaProviderType.OPEN_AI, quota = null, error = "Network timeout", ProviderAuthState.AUTHENTICATED),
        )
        assertEquals(
            "OpenAI • not logged in",
            buildIndicatorTooltip(QuotaProviderType.OPEN_AI, quota = null, error = null, ProviderAuthState.UNAUTHENTICATED),
        )
    }

    @Test
    fun buildIncludesOpenAiPlanAndOmitsInventedFallback() {
        assertEquals(
            "OpenAI • Plus • 1% 5-hour",
            buildIndicatorTooltip(
                QuotaProviderType.OPEN_AI,
                OpenAiCodexQuota(
                    planType = "plus",
                    primary = de.moritzf.quota.openai.UsageWindow(
                        usedPercent = 1.0,
                        windowDuration = java.time.Duration.ofHours(5),
                    ),
                ),
                error = null,
                ProviderAuthState.AUTHENTICATED,
            ),
        )
        assertNull(indicatorPlanName(OpenAiCodexQuota(primary = de.moritzf.quota.openai.UsageWindow(usedPercent = 1.0))))
        assertEquals(
            "OpenAI (Work) • Plus • 1% 5-hour",
            buildIndicatorTooltip(
                QuotaProviderType.OPEN_AI,
                OpenAiCodexQuota(
                    planType = "plus",
                    primary = de.moritzf.quota.openai.UsageWindow(
                        usedPercent = 1.0,
                        windowDuration = java.time.Duration.ofHours(5),
                    ),
                ),
                error = null,
                ProviderAuthState.AUTHENTICATED,
                accountName = "Work",
            ),
        )
    }

    @Test
    fun buildKeepsSameShapeAcrossProviders() {
        val resetsAt = Clock.System.now().plus(2.hours)
        assertEquals(
            "Claude • Pro • 33% 5-hour",
            buildIndicatorTooltip(
                QuotaProviderType.CLAUDE,
                ClaudeQuota(plan = "Pro", fiveHourUsage = ClaudeUsageWindow(usagePercent = 33.0)),
                error = null,
                ProviderAuthState.AUTHENTICATED,
            ),
        )
        assertEquals(
            "Kimi • Kimi Code",
            buildIndicatorTooltip(
                QuotaProviderType.KIMI,
                KimiQuota(plan = "Kimi Code"),
                error = null,
                ProviderAuthState.AUTHENTICATED,
            ),
        )
        assertEquals(
            "MiniMax • Coding Plan • 20% Session",
            buildIndicatorTooltip(
                QuotaProviderType.MINIMAX,
                MiniMaxQuota(plan = "Coding Plan", sessionUsage = MiniMaxUsageWindow(usagePercent = 20.0)),
                error = null,
                ProviderAuthState.AUTHENTICATED,
            ),
        )
        assertEquals(
            "MiniMax • MiniMax Token Plan • 100% Weekly",
            buildIndicatorTooltip(
                QuotaProviderType.MINIMAX,
                MiniMaxQuota(
                    plan = "MiniMax Token Plan",
                    sessionUsage = MiniMaxUsageWindow(usagePercent = 40.0),
                    weeklyUsage = MiniMaxUsageWindow(usagePercent = 100.0),
                ),
                error = null,
                ProviderAuthState.AUTHENTICATED,
            ),
        )
        assertEquals(
            "SuperGrok • SuperGrok Heavy",
            buildIndicatorTooltip(
                QuotaProviderType.SUPERGROK,
                SuperGrokQuota(plan = "SuperGrok Heavy"),
                error = null,
                ProviderAuthState.AUTHENTICATED,
            ),
        )
        assertEquals(
            "Z.ai • Pro",
            buildIndicatorTooltip(
                QuotaProviderType.ZAI,
                ZaiQuota(plan = "Pro"),
                error = null,
                ProviderAuthState.AUTHENTICATED,
            ),
        )
        assertEquals(
            "Cursor • Pro • 12% Included",
            buildIndicatorTooltip(
                QuotaProviderType.CURSOR,
                CursorQuota(planName = "Pro", planUsage = de.moritzf.quota.cursor.CursorPlanUsage(totalPercentUsed = 12.0)),
                error = null,
                ProviderAuthState.AUTHENTICATED,
            ),
        )
        assertEquals(
            "GitHub Copilot • Copilot Individual",
            buildIndicatorTooltip(
                QuotaProviderType.GITHUB,
                GitHubQuota(plan = "Copilot Individual"),
                error = null,
                ProviderAuthState.AUTHENTICATED,
            ),
        )
        assertEquals(
            "OpenCode",
            buildIndicatorTooltip(
                QuotaProviderType.OPEN_CODE,
                OpenCodeQuota(availableBalance = 1_000_000_000L, useBalance = true),
                error = null,
                ProviderAuthState.AUTHENTICATED,
            ),
        )
        val ollamaReset = buildIndicatorTooltip(
            QuotaProviderType.OLLAMA,
            de.moritzf.quota.ollama.OllamaQuota(
                sessionUsage = de.moritzf.quota.ollama.OllamaUsageWindow(usagePercent = 8.0, resetsAt = resetsAt),
            ),
            error = null,
            ProviderAuthState.AUTHENTICATED,
        )
        assertTrue(ollamaReset.startsWith("Ollama • 8% Session • Resets in "))
    }
}
