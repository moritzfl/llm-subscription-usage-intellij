package de.moritzf.quota.idea.ui.indicator

import de.moritzf.quota.claude.ClaudeQuota
import de.moritzf.quota.claude.ClaudeUsageWindow
import de.moritzf.quota.cursor.CursorPlanUsage
import de.moritzf.quota.cursor.CursorQuota
import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.github.GitHubQuota
import de.moritzf.quota.github.GitHubUsageWindow
import de.moritzf.quota.kimi.KimiQuota
import de.moritzf.quota.kimi.KimiUsageWindow
import de.moritzf.quota.minimax.MiniMaxQuota
import de.moritzf.quota.minimax.MiniMaxUsageWindow
import de.moritzf.quota.ollama.OllamaQuota
import de.moritzf.quota.ollama.OllamaUsageWindow
import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.openai.UsageWindow
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.opencode.OpenCodeUsageWindow
import de.moritzf.quota.supergrok.SuperGrokQuota
import de.moritzf.quota.supergrok.SuperGrokUsageWindow
import de.moritzf.quota.zai.ZaiCountUsageWindow
import de.moritzf.quota.zai.ZaiQuota
import de.moritzf.quota.zai.ZaiUsageWindow
import kotlin.time.Clock
import kotlin.time.Instant
import java.time.Duration

internal object QuotaPeriodDurations {
    val ROLLING_5H: Duration = Duration.ofHours(5)
    val WEEKLY: Duration = Duration.ofDays(7)
    val MONTHLY: Duration = Duration.ofDays(30)
}

internal fun computePeriodElapsedFraction(
    periodDurationMs: Long,
    resetsAt: Instant,
    now: Instant = Clock.System.now(),
): Double? {
    if (periodDurationMs <= 0L) {
        return null
    }
    val resetMs = resetsAt.toEpochMilliseconds()
    val nowMs = now.toEpochMilliseconds()
    if (nowMs >= resetMs) {
        return 1.0
    }
    val remainingMs = resetMs - nowMs
    val elapsedMs = periodDurationMs - remainingMs
    if (elapsedMs <= 0L) {
        return 0.0
    }
    return (elapsedMs.toDouble() / periodDurationMs).coerceIn(0.0, 1.0)
}

internal fun computePeriodElapsedBetween(
    periodStart: Instant,
    periodEnd: Instant,
    now: Instant = Clock.System.now(),
): Double? {
    val totalMs = periodEnd.toEpochMilliseconds() - periodStart.toEpochMilliseconds()
    if (totalMs <= 0L) {
        return null
    }
    val elapsedMs = now.toEpochMilliseconds() - periodStart.toEpochMilliseconds()
    return (elapsedMs.toDouble() / totalMs).coerceIn(0.0, 1.0)
}

internal fun UsageWindow.periodElapsedFraction(now: Instant = Clock.System.now()): Double? {
    val durationMs = windowDuration?.toMillis() ?: return null
    val resetAt = resetsAt ?: return null
    return computePeriodElapsedFraction(durationMs, resetAt, now)
}

internal fun OpenCodeUsageWindow.periodElapsedFraction(periodDuration: Duration): Double? {
    val periodDurationSec = periodDuration.seconds
    if (periodDurationSec <= 0L || resetInSec <= 0L) {
        return null
    }
    return (1.0 - resetInSec.toDouble() / periodDurationSec).coerceIn(0.0, 1.0)
}

internal fun OllamaUsageWindow.periodElapsedFraction(periodDuration: Duration, now: Instant = Clock.System.now()): Double? {
    val resetAt = resetsAt ?: return null
    return computePeriodElapsedFraction(periodDuration.toMillis(), resetAt, now)
}

internal fun ZaiUsageWindow.periodElapsedFraction(periodDuration: Duration, now: Instant = Clock.System.now()): Double? {
    val resetAt = resetsAt ?: return null
    return computePeriodElapsedFraction(periodDuration.toMillis(), resetAt, now)
}

internal fun ZaiCountUsageWindow.periodElapsedFraction(periodDuration: Duration, now: Instant = Clock.System.now()): Double? {
    val resetAt = resetsAt ?: return null
    return computePeriodElapsedFraction(periodDuration.toMillis(), resetAt, now)
}

internal fun MiniMaxUsageWindow.periodElapsedFraction(now: Instant = Clock.System.now()): Double? {
    val durationMs = periodDurationMs ?: return null
    val resetAt = resetsAt ?: return null
    return computePeriodElapsedFraction(durationMs, resetAt, now)
}

internal fun KimiUsageWindow.periodElapsedFraction(now: Instant = Clock.System.now()): Double? {
    val durationMs = periodDurationMs ?: return null
    val resetAt = resetsAt ?: return null
    return computePeriodElapsedFraction(durationMs, resetAt, now)
}

internal fun GitHubUsageWindow.periodElapsedFraction(now: Instant = Clock.System.now()): Double? {
    val durationMs = periodDurationMs ?: return null
    val resetAt = resetsAt ?: return null
    return computePeriodElapsedFraction(durationMs, resetAt, now)
}

internal fun SuperGrokUsageWindow.periodElapsedFraction(now: Instant = Clock.System.now()): Double? {
    val durationMs = periodDurationMs ?: return null
    val resetAt = resetsAt ?: return null
    return computePeriodElapsedFraction(durationMs, resetAt, now)
}

internal fun ClaudeUsageWindow.periodElapsedFraction(now: Instant = Clock.System.now()): Double? {
    val durationMs = periodDurationMs ?: return null
    val resetAt = resetsAt ?: return null
    return computePeriodElapsedFraction(durationMs, resetAt, now)
}

internal fun CursorPlanUsage.periodElapsedFraction(now: Instant = Clock.System.now()): Double? {
    val end = billingCycleEnd ?: return null
    val start = billingCycleStart
    if (start != null) {
        return computePeriodElapsedBetween(start, end, now)
    }
    return null
}

internal fun indicatorPeriodElapsedFraction(data: QuotaIndicatorData): Double? {
    return ProviderUiRegistry.forType(data.type).periodElapsedFraction(data.quota, data.error)
}

internal fun openAiPeriodElapsedFraction(quota: OpenAiCodexQuota?, error: String?): Double? {
    val authService = QuotaAuthService.getInstance()
    if (!authService.isLoggedIn(QuotaProviderType.OPEN_AI) || error != null) {
        return null
    }
    val state = indicatorQuotaState(quota) ?: return null
    val window = state.window ?: limitingWindow(quota, state.kind) ?: return null
    return window.periodElapsedFraction()
}

internal fun openCodePeriodElapsedFraction(quota: OpenCodeQuota?, error: String?): Double? {
    if (error != null || quota == null) {
        return null
    }
    val windows = listOfNotNull(
        quota.rollingUsage?.let { it to QuotaPeriodDurations.ROLLING_5H },
        quota.weeklyUsage?.let { it to QuotaPeriodDurations.WEEKLY },
        quota.monthlyUsage?.let { it to QuotaPeriodDurations.MONTHLY },
    )
    if (windows.isEmpty()) {
        return null
    }

    val exhausted = windows.filter { (window, _) -> window.isExhausted() }
    if (exhausted.isNotEmpty()) {
        val (window, duration) = exhausted.maxBy { (window, _) -> window.resetInSec }
        return window.periodElapsedFraction(duration)
    }

    val (window, duration) = windows.first()
    return window.periodElapsedFraction(duration)
}

private fun OpenCodeUsageWindow.isExhausted(): Boolean {
    return isRateLimited || usagePercent >= 100
}

internal fun ollamaPeriodElapsedFraction(quota: OllamaQuota?, error: String?): Double? {
    if (error != null || quota == null) {
        return null
    }
    val windows = listOfNotNull(
        quota.sessionUsage?.let { it to QuotaPeriodDurations.ROLLING_5H },
        quota.weeklyUsage?.let { it to QuotaPeriodDurations.WEEKLY },
    )
    if (windows.isEmpty()) {
        return null
    }

    val exhausted = windows.filter { (window, _) -> window.usagePercent >= 100.0 }
    if (exhausted.isNotEmpty()) {
        val (window, duration) = exhausted.maxBy { (window, _) -> window.resetsAt?.toEpochMilliseconds() ?: Long.MIN_VALUE }
        return window.periodElapsedFraction(duration)
    }

    val (window, duration) = windows.first()
    return window.periodElapsedFraction(duration)
}

internal fun zaiPeriodElapsedFraction(quota: ZaiQuota?, error: String?): Double? {
    if (error != null || quota == null) {
        return null
    }
    val windows = listOfNotNull(
        quota.sessionUsage?.let { it to QuotaPeriodDurations.ROLLING_5H },
        quota.weeklyUsage?.let { it to QuotaPeriodDurations.WEEKLY },
    )
    if (windows.isEmpty()) {
        return quota.webSearchUsage?.periodElapsedFraction(QuotaPeriodDurations.MONTHLY)
    }

    val exhausted = windows.filter { (window, _) -> window.usagePercent >= 100.0 }
    if (exhausted.isNotEmpty()) {
        val (window, duration) = exhausted.maxBy { (window, _) -> window.resetsAt?.toEpochMilliseconds() ?: Long.MIN_VALUE }
        return window.periodElapsedFraction(duration)
    }

    val (window, duration) = windows.first()
    return window.periodElapsedFraction(duration)
}

internal fun miniMaxPeriodElapsedFraction(quota: MiniMaxQuota?, error: String?): Double? {
    if (error != null || quota == null) {
        return null
    }
    return quota.sessionUsage?.periodElapsedFraction()
}

internal fun kimiPeriodElapsedFraction(quota: KimiQuota?, error: String?): Double? {
    if (error != null || quota == null) {
        return null
    }
    return (quota.sessionUsage ?: quota.totalUsage)?.periodElapsedFraction()
}

internal fun gitHubPeriodElapsedFraction(quota: GitHubQuota?, error: String?): Double? {
    if (error != null || quota == null) {
        return null
    }
    return quota.limitedWindows().firstOrNull()?.periodElapsedFraction()
}

internal fun cursorPeriodElapsedFraction(quota: CursorQuota?, error: String?): Double? {
    if (error != null || quota == null) {
        return null
    }
    return quota.planUsage?.periodElapsedFraction()
}

internal fun superGrokPeriodElapsedFraction(quota: SuperGrokQuota?, error: String?): Double? {
    if (error != null || quota == null) {
        return null
    }
    return quota.creditUsage?.periodElapsedFraction()
}

internal fun claudePeriodElapsedFraction(quota: ClaudeQuota?, error: String?): Double? {
    if (error != null || quota == null) {
        return null
    }
    return quota.primaryWindow()?.periodElapsedFraction()
}
