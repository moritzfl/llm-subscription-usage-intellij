package de.moritzf.quota.idea

import de.moritzf.quota.cursor.CursorPlanUsage
import de.moritzf.quota.cursor.CursorQuota
import de.moritzf.quota.idea.ui.indicator.computePeriodElapsedFraction
import de.moritzf.quota.idea.ui.indicator.indicatorPeriodElapsedFraction
import de.moritzf.quota.idea.ui.indicator.periodElapsedFraction
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.ui.indicator.QuotaIndicatorData
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.opencode.OpenCodeUsageWindow
import de.moritzf.quota.openai.UsageWindow
import kotlinx.datetime.Instant
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days

class QuotaPeriodElapsedTest {
    @Test
    fun computePeriodElapsedFractionUsesRemainingWindowTime() {
        val now = Instant.fromEpochMilliseconds(1_000_000L)
        val resetsAt = now + 3.days
        val durationMs = 7.days.inWholeMilliseconds

        val elapsed = computePeriodElapsedFraction(durationMs, resetsAt, now)

        assertEquals(0.5714285714285714, elapsed)
    }

    @Test
    fun usageWindowPeriodElapsedUsesWindowDuration() {
        val now = Instant.fromEpochMilliseconds(0L)
        val window = UsageWindow(
            usedPercent = 42.0,
            windowDuration = Duration.ofDays(7),
            resetsAt = now + 3.days,
        )

        assertEquals(4.0 / 7.0, window.periodElapsedFraction(now))
    }

    @Test
    fun openCodeRollingWindowComputesElapsedFromResetInSec() {
        val quota = OpenCodeQuota(
            rollingUsage = OpenCodeUsageWindow(usagePercent = 42, resetInSec = 9_000),
        )

        val elapsed = indicatorPeriodElapsedFraction(QuotaIndicatorData(QuotaProviderType.OPEN_CODE, quota, error = null))

        assertEquals(0.5, elapsed)
    }

    @Test
    fun cursorPlanUsageComputesElapsedFromBillingCycle() {
        val start = Instant.fromEpochMilliseconds(0L)
        val end = Instant.fromEpochMilliseconds(10_000L)
        val now = Instant.fromEpochMilliseconds(4_000L)
        val usage = CursorPlanUsage(
            totalPercentUsed = 40.0,
            billingCycleStart = start,
            billingCycleEnd = end,
        )

        assertEquals(0.4, usage.periodElapsedFraction(now))
    }

    @Test
    fun cursorWithoutBillingCycleStartReturnsNull() {
        val usage = CursorPlanUsage(
            totalPercentUsed = 40.0,
            billingCycleEnd = Instant.fromEpochMilliseconds(10_000L),
        )

        assertNull(usage.periodElapsedFraction())
    }
}
