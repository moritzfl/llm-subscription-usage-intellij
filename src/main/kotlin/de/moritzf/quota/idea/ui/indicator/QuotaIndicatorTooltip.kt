package de.moritzf.quota.idea.ui.indicator

import de.moritzf.quota.claude.ClaudeQuota
import de.moritzf.quota.antigravity.AntigravityQuota
import de.moritzf.quota.cursor.CursorQuota
import de.moritzf.quota.github.GitHubQuota
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.ui.popup.toDisplayLabel
import de.moritzf.quota.kimi.KimiQuota
import de.moritzf.quota.minimax.MiniMaxQuota
import de.moritzf.quota.mistral.MistralQuota
import de.moritzf.quota.ollama.OllamaQuota
import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.shared.ProviderQuota
import de.moritzf.quota.supergrok.SuperGrokQuota
import de.moritzf.quota.zai.ZaiQuota
import java.time.Duration
import kotlin.math.roundToInt
import kotlin.time.Instant

private const val TOOLTIP_SEPARATOR = " • "

internal fun formatIndicatorTooltip(
    provider: String,
    plan: String? = null,
    percent: Int? = null,
    windowKind: String? = null,
    reset: String? = null,
    status: String? = null,
): String {
    val parts = buildList {
        add(provider.trim())
        val visiblePlan = normalizedPlan(provider, plan)
        visiblePlan?.let(::add)
        percent?.let { value ->
            val kind = windowKind?.trim()?.takeIf { it.isNotEmpty() }
            add(if (kind != null) "${clampPercent(value)}% $kind" else "${clampPercent(value)}%")
        }
        reset?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
        if (visiblePlan == null && percent == null && reset.isNullOrBlank()) {
            status?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
        }
    }
    return parts.joinToString(TOOLTIP_SEPARATOR)
}

internal fun buildIndicatorTooltip(
    type: QuotaProviderType,
    quota: ProviderQuota?,
    error: String?,
    authState: ProviderAuthState,
    accountName: String? = null,
): String {
    val providerLabel = accountName?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { "${type.displayName} ($it)" }
        ?: type.displayName
    val plan = indicatorPlanName(quota)
    val usage = indicatorTooltipUsage(quota)
    if (plan != null || usage.percent != null || usage.reset != null) {
        return formatIndicatorTooltip(
            provider = providerLabel,
            plan = plan,
            percent = usage.percent,
            windowKind = usage.windowKind,
            reset = usage.reset,
        )
    }
    return formatIndicatorTooltip(
        provider = providerLabel,
        status = when {
            error != null -> error
            authState == ProviderAuthState.UNAUTHENTICATED -> "not logged in"
            quota == null -> "loading"
            else -> null
        },
    )
}

internal fun indicatorPlanName(quota: ProviderQuota?): String? {
    val raw = when (quota) {
        is OpenAiCodexQuota -> quota.planType?.toDisplayLabel()
        is ClaudeQuota -> quota.plan
        is CursorQuota -> quota.planName.ifBlank { quota.membershipType }
        is GitHubQuota -> quota.plan
        is KimiQuota -> quota.plan
        is MiniMaxQuota -> quota.plan
        is SuperGrokQuota -> quota.plan
        is ZaiQuota -> quota.plan
        else -> null
    }
    return raw?.trim()?.takeIf { it.isNotEmpty() }
}

private fun normalizedPlan(provider: String, plan: String?): String? {
    val trimmed = plan?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (trimmed.equals(provider, ignoreCase = true)) return null
    return trimmed
}

private data class IndicatorTooltipUsage(
    val percent: Int?,
    val reset: String?,
    val windowKind: String? = null,
)

private fun indicatorTooltipUsage(quota: ProviderQuota?): IndicatorTooltipUsage {
    return when (quota) {
        is AntigravityQuota -> {
            val window = quota.primaryWindow() ?: return IndicatorTooltipUsage(null, null)
            val kind = listOfNotNull(window.group, window.window).joinToString(" / ")
            IndicatorTooltipUsage(window.usagePercent?.roundToInt(), compactReset(window.resetsAt), "used ($kind)")
        }
        is OpenAiCodexQuota -> openAiTooltipUsage(quota)
        is OpenCodeQuota -> {
            val state = openCodeIndicatorState(quota) ?: return IndicatorTooltipUsage(null, null)
            IndicatorTooltipUsage(
                state.percent,
                resetLabel(formatOpenCodeResetTime(state.resetInSec)),
                openCodeWindowKind(quota),
            )
        }
        is OllamaQuota -> {
            val state = ollamaIndicatorState(quota) ?: return IndicatorTooltipUsage(null, null)
            IndicatorTooltipUsage(
                state.percent,
                compactReset(state.resetsAt),
                ollamaWindowKind(state.period),
            )
        }
        is ZaiQuota -> {
            val state = zaiIndicatorState(quota) ?: return IndicatorTooltipUsage(null, null)
            IndicatorTooltipUsage(state.percent, compactReset(state.resetsAt), zaiWindowKind(quota))
        }
        is MiniMaxQuota -> {
            val state = miniMaxIndicatorState(quota) ?: return IndicatorTooltipUsage(null, null)
            IndicatorTooltipUsage(state.percent, compactReset(state.resetsAt), miniMaxWindowKind(quota))
        }
        is MistralQuota -> {
            val window = mistralDisplayWindow(quota) ?: return IndicatorTooltipUsage(null, null)
            val kind = when {
                window === quota.monthlyUsage -> "Monthly"
                window === quota.tokenUsage -> "Tokens / min"
                window === quota.requestUsage -> "Requests / min"
                else -> null
            }
            IndicatorTooltipUsage(
                clampPercent(window.usagePercent.roundToInt()),
                if (isMistralPerMinuteWindow(window)) null else compactReset(window.resetsAt),
                kind,
            )
        }
        is KimiQuota -> {
            val window = kimiDisplayWindow(quota) ?: return IndicatorTooltipUsage(null, null)
            val kind = when {
                window === quota.sessionUsage -> "Session"
                window === quota.totalUsage -> "Overall"
                else -> null
            }
            IndicatorTooltipUsage(
                clampPercent(window.usagePercent.roundToInt()),
                compactReset(window.resetsAt),
                kind,
            )
        }
        is GitHubQuota -> {
            val window = gitHubDisplayWindow(quota) ?: return IndicatorTooltipUsage(null, null)
            IndicatorTooltipUsage(
                clampPercent(window.usagePercent.roundToInt()),
                compactReset(window.resetsAt),
                window.label.trim().takeIf { it.isNotEmpty() },
            )
        }
        is CursorQuota -> {
            val state = cursorIndicatorState(quota) ?: return IndicatorTooltipUsage(null, null)
            IndicatorTooltipUsage(state.percent, compactReset(state.resetsAt), cursorWindowKind(quota))
        }
        is SuperGrokQuota -> {
            val state = superGrokIndicatorState(quota) ?: return IndicatorTooltipUsage(null, null)
            IndicatorTooltipUsage(
                state.percent,
                compactReset(state.resetsAt),
                windowKindFromDuration(quota.creditUsage?.periodDuration),
            )
        }
        is ClaudeQuota -> {
            val state = claudeIndicatorState(quota) ?: return IndicatorTooltipUsage(null, null)
            IndicatorTooltipUsage(state.percent, compactReset(state.resetsAt), claudeWindowKind(quota))
        }
        else -> IndicatorTooltipUsage(null, null)
    }
}

private fun openAiTooltipUsage(quota: OpenAiCodexQuota): IndicatorTooltipUsage {
    val state = indicatorQuotaState(quota) ?: return IndicatorTooltipUsage(null, null)
    val display = openAiIndicatorDisplayState(quota, state) ?: return IndicatorTooltipUsage(null, null)
    if (display.creditsBalanceLabel != null) {
        return IndicatorTooltipUsage(null, null)
    }
    val window = if (state.limitReached || display.percent >= 100) {
        limitingWindow(quota, state.kind) ?: state.window
    } else {
        state.window
    }
    val kind = windowKindFromDuration(window?.windowDuration)
        ?: if (state.kind == IndicatorQuotaKind.REVIEW) "Review" else null
    return IndicatorTooltipUsage(display.percent, compactReset(display.resetsAt), kind)
}

private fun openCodeWindowKind(quota: OpenCodeQuota): String? {
    val windows = listOfNotNull(quota.rollingUsage, quota.weeklyUsage, quota.monthlyUsage)
    val selected = windows.filter { it.isRateLimited || it.usagePercent >= 100 }
        .maxByOrNull { it.resetInSec }
        ?: windows.firstOrNull()
        ?: return null
    return when {
        selected === quota.rollingUsage -> "5-hour"
        selected === quota.weeklyUsage -> "Weekly"
        selected === quota.monthlyUsage -> "Monthly"
        else -> null
    }
}

private fun ollamaWindowKind(period: Duration): String? {
    return when (period) {
        QuotaPeriodDurations.ROLLING_5H -> "Session"
        QuotaPeriodDurations.WEEKLY -> "Weekly"
        QuotaPeriodDurations.MONTHLY -> "Monthly"
        else -> windowKindFromDuration(period)
    }
}

private fun miniMaxWindowKind(quota: MiniMaxQuota): String? {
    val windows = listOfNotNull(
        quota.sessionUsage?.let { it to "Session" },
        quota.weeklyUsage?.let { it to "Weekly" },
    )
    if (windows.isEmpty()) return null
    val exhausted = windows.filter { (window, _) -> window.usagePercent >= 100.0 }
    if (exhausted.isNotEmpty()) {
        return exhausted.maxBy { (window, _) -> window.resetsAt?.toEpochMilliseconds() ?: Long.MIN_VALUE }.second
    }
    return windows.first().second
}

private fun zaiWindowKind(quota: ZaiQuota): String? {
    val windows = listOfNotNull(
        quota.sessionUsage?.let { it to "Session" },
        quota.weeklyUsage?.let { it to "Weekly" },
    )
    if (windows.isEmpty()) {
        return if (quota.webSearchUsage != null) "Search" else null
    }
    val exhausted = windows.filter { (window, _) -> window.usagePercent >= 100.0 }
    if (exhausted.isNotEmpty()) {
        return exhausted.maxBy { (window, _) -> window.resetsAt?.toEpochMilliseconds() ?: Long.MIN_VALUE }.second
    }
    return windows.first().second
}

private fun cursorWindowKind(quota: CursorQuota): String? {
    return when {
        quota.requestUsage?.usagePercent() != null -> "Requests"
        quota.planUsage != null -> "Included"
        quota.spendLimit?.usagePercent() != null -> "Spend"
        quota.onDemandUsage?.usagePercent() != null -> "On-demand"
        quota.teamOnDemandUsage?.usagePercent() != null -> "Team"
        else -> null
    }
}

private fun claudeWindowKind(quota: ClaudeQuota): String? {
    val window = quota.primaryWindow()
    if (window != null) {
        return when {
            window === quota.fiveHourUsage -> "5-hour"
            window === quota.sevenDayUsage -> "Weekly"
            else -> window.label.trim().takeIf { it.isNotEmpty() }
        }
    }
    return if (quota.extraUsage?.isEnabled == true) "Extra" else null
}

internal fun windowKindFromDuration(duration: Duration?): String? {
    val minutes = duration?.toMinutes() ?: return null
    return when {
        minutes in 295L..305L -> "5-hour"
        minutes in 10070L..10090L -> "Weekly"
        minutes in 43190L..43210L -> "Monthly"
        minutes >= 10080L && minutes % 10080L == 0L -> {
            val weeks = minutes / 10080L
            if (weeks == 1L) "Weekly" else "$weeks-week"
        }
        minutes >= 1440L && minutes % 1440L == 0L -> {
            val days = minutes / 1440L
            if (days == 1L) "Daily" else "$days-day"
        }
        minutes >= 60L && minutes % 60L == 0L -> {
            val hours = minutes / 60L
            if (hours == 1L) "Hourly" else "$hours-hour"
        }
        else -> null
    }
}

private fun compactReset(resetsAt: Instant?): String? = resetLabel(QuotaUiUtil.formatResetCompact(resetsAt))

private fun resetLabel(compact: String?): String? {
    val text = compact?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return "Resets in $text"
}
