package de.moritzf.quota.idea

import de.moritzf.quota.idea.ui.indicator.QuotaIndicatorComponent
import de.moritzf.quota.idea.ui.indicator.QuotaIndicatorData
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
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.openai.UsageWindow
import de.moritzf.quota.opencode.OpenCodeUsageWindow
import kotlinx.datetime.Clock
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun openCodeBarDisplayTextShowsNoDataWithoutBalance() {
        val quota = OpenCodeQuota(
            availableBalance = 1_000_000_000L,
            useBalance = true,
        )

        val text = openCodeBarDisplayText(quota, error = null)
        assertEquals("no data", text)
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
