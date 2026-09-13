package de.moritzf.quota.idea.ui.indicator

import com.intellij.ui.components.JBLabel
import de.moritzf.quota.idea.settings.QuotaDisplayMode
import de.moritzf.quota.idea.ui.QuotaUiUtil
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.openai.isAssignedCreditsQuota
import de.moritzf.quota.openai.isCreditsDepleted
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.opencode.OpenCodeUsageWindow
import de.moritzf.quota.openai.UsageWindow
import de.moritzf.quota.zai.ZaiQuota
import de.moritzf.quota.cursor.CursorQuota
import de.moritzf.quota.minimax.MiniMaxQuota
import de.moritzf.quota.mistral.MistralQuota
import de.moritzf.quota.github.GitHubQuota
import de.moritzf.quota.github.GitHubSubscriptionState
import de.moritzf.quota.claude.ClaudeQuota
import de.moritzf.quota.kimi.KimiQuota
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.supergrok.SuperGrokQuota
import java.awt.Component
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.Locale
import kotlin.time.Instant
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.SwingConstants
import kotlin.math.roundToInt

internal enum class IndicatorQuotaKind {
    CODEX,
    REVIEW,
}

internal data class IndicatorQuotaState(
    val kind: IndicatorQuotaKind,
    val window: UsageWindow?,
    val limitReached: Boolean,
)

internal data class IndicatorDisplayState(
    val percent: Int,
    val resetsAt: Instant?,
    val creditsBalanceLabel: String? = null,
)

internal data class OpenCodeIndicatorState(
    val percent: Int,
    val resetInSec: Long,
)

internal data class OllamaIndicatorState(
    val percent: Int,
    val resetsAt: Instant?,
    val period: java.time.Duration,
)

internal data class MiniMaxIndicatorState(
    val percent: Int,
    val resetsAt: Instant?,
)

internal data class ZaiIndicatorState(
    val percent: Int,
    val resetsAt: Instant?,
)

internal data class SuperGrokIndicatorState(
    val percent: Int,
    val resetsAt: Instant?,
)

internal data class ClaudeIndicatorState(
    val percent: Int,
    val resetsAt: Instant?,
)

internal class QuotaIndicatorComponent(
    horizontalPadding: Int,
    private val onClick: (Component, QuotaIndicatorData) -> Unit,
) : BorderLayoutPanel() {
    private val statusIconLabel = createStatusIconLabel()
    private val sourceIconLabel = createSourceIconLabel()
    private val percentageComponent = QuotaPercentageIndicator()
    private var data: QuotaIndicatorData = QuotaIndicatorData(QuotaProviderType.OPEN_AI, quota = null, error = null)
    private val clickListener = object : MouseAdapter() {
        override fun mouseClicked(event: MouseEvent) {
            onClick(this@QuotaIndicatorComponent, data)
        }
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(0, horizontalPadding)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = " "
        addMouseListener(clickListener)
        statusIconLabel.addMouseListener(clickListener)
        sourceIconLabel.addMouseListener(clickListener)
        percentageComponent.addMouseListener(clickListener)
        statusIconLabel.cursor = cursor
        sourceIconLabel.cursor = cursor
        percentageComponent.cursor = cursor
    }

    fun updateUsage(data: QuotaIndicatorData, displayMode: QuotaDisplayMode) {
        this.data = data
        val tooltip = ProviderUiRegistry.forType(data.type).tooltip(data.quota, data.error, data.accountId)
        toolTipText = tooltip
        statusIconLabel.toolTipText = tooltip
        sourceIconLabel.toolTipText = tooltip
        percentageComponent.toolTipText = tooltip

        when (displayMode) {
            QuotaDisplayMode.ICON_ONLY -> {
                statusIconLabel.icon = QuotaIcons.STATUS
                showContent(statusIconLabel)
            }

            QuotaDisplayMode.CAKE_DIAGRAM -> {
                statusIconLabel.icon = scaledCakeIcon(statusIconLabel)
                showContent(statusIconLabel)
            }

            QuotaDisplayMode.PERCENTAGE_BAR -> {
                updatePercentageDisplay()
                val icon = resolveSourceIcon()
                sourceIconLabel.icon = scaledSourceIcon(icon, sourceIconLabel)
                showPercentageContent(sourceIconLabel, percentageComponent)
            }
        }

        revalidate()
        repaint()
    }

    override fun getToolTipText(event: MouseEvent?): String {
        return ProviderUiRegistry.forType(data.type).tooltip(data.quota, data.error, data.accountId)
    }

    private fun showContent(component: JComponent) {
        removeAll()
        addToCenter(component)
    }

    private fun showPercentageContent(iconLabel: JComponent, percentageComponent: QuotaPercentageIndicator) {
        removeAll()
        val wrapper = BorderLayoutPanel().apply { isOpaque = false }
        wrapper.addToLeft(iconLabel)
        wrapper.addToCenter(percentageComponent)
        addToCenter(wrapper)
    }

    private fun resolveSourceIcon(): Icon {
        return ProviderUiRegistry.forType(data.type).icon
    }

    private fun createStatusIconLabel(): JBLabel {
        return JBLabel().apply {
            horizontalAlignment = SwingConstants.CENTER
            verticalAlignment = SwingConstants.CENTER
        }
    }

    private fun createSourceIconLabel(): JBLabel {
        return JBLabel().apply {
            horizontalAlignment = SwingConstants.CENTER
            verticalAlignment = SwingConstants.CENTER
            border = JBUI.Borders.emptyRight(3)
        }
    }

    private fun barDisplayText(): String {
        return ProviderUiRegistry.forType(data.type).barText(data.quota, data.error, data.accountId)
    }

    private fun cakeIcon(): Icon {
        val percent = ProviderUiRegistry.forType(data.type).cakePercent(data.quota, data.error, data.accountId)
        if (percent < 0) {
            return QuotaIcons.CAKE_UNKNOWN
        }
        return cakeIconForPercent(percent)
    }

    private fun cakeIconForPercent(percent: Int): Icon {
        if (percent >= 100) {
            return QuotaIcons.CAKE_100
        }
        if (percent <= 0) {
            return QuotaIcons.CAKE_0
        }

        val bucket = minOf(95, ((percent + 4) / 5) * 5)
        return when (bucket) {
            5 -> QuotaIcons.CAKE_5
            10 -> QuotaIcons.CAKE_10
            15 -> QuotaIcons.CAKE_15
            20 -> QuotaIcons.CAKE_20
            25 -> QuotaIcons.CAKE_25
            30 -> QuotaIcons.CAKE_30
            35 -> QuotaIcons.CAKE_35
            40 -> QuotaIcons.CAKE_40
            45 -> QuotaIcons.CAKE_45
            50 -> QuotaIcons.CAKE_50
            55 -> QuotaIcons.CAKE_55
            60 -> QuotaIcons.CAKE_60
            65 -> QuotaIcons.CAKE_65
            70 -> QuotaIcons.CAKE_70
            75 -> QuotaIcons.CAKE_75
            80 -> QuotaIcons.CAKE_80
            85 -> QuotaIcons.CAKE_85
            90 -> QuotaIcons.CAKE_90
            95 -> QuotaIcons.CAKE_95
            else -> QuotaIcons.CAKE_UNKNOWN
        }
    }

    private fun scaledCakeIcon(component: JComponent): Icon {
        return scaleIconToQuotaStatusSize(cakeIcon(), component)
    }

    private fun scaledSourceIcon(icon: Icon, component: JComponent): Icon {
        val targetSize = JBUI.scale(13)
        val iconWidth = icon.iconWidth
        val iconHeight = icon.iconHeight
        if (iconWidth <= 0 || iconHeight <= 0) {
            return icon
        }
        if (iconWidth <= targetSize && iconHeight <= targetSize) {
            return icon
        }

        val widthScale = targetSize / iconWidth.toFloat()
        val heightScale = targetSize / iconHeight.toFloat()
        return IconUtil.scale(icon, component, minOf(widthScale, heightScale))
    }

    private fun displayPercent(): Int {
        return ProviderUiRegistry.forType(data.type).displayPercent(data.quota, data.error, data.accountId)
    }

    private fun updatePercentageDisplay() {
        val percentage = displayPercent()
        val text = barDisplayText()
        val periodElapsedFraction = indicatorPeriodElapsedFraction(data)
        if (percentage >= 0) {
            percentageComponent.update(
                text = text,
                fraction = percentage / 100.0,
                fillColor = QuotaUsageColors.usageColor(percentage),
                periodElapsedFraction = periodElapsedFraction,
            )
        } else {
            percentageComponent.update(
                text = text,
                fraction = 0.0,
                fillColor = QuotaUsageColors.GRAY,
                periodElapsedFraction = periodElapsedFraction,
            )
        }
    }
}

internal fun ollamaBarDisplayText(quota: de.moritzf.quota.ollama.OllamaQuota?, error: String?): String {
    if (error != null) return "error"
    if (quota == null) return "loading..."

    val state = ollamaIndicatorState(quota) ?: return "no data"
    return formatPercentWithOptionalTime(state.percent, state.resetsAt, state.period)
}

internal fun miniMaxBarDisplayText(quota: MiniMaxQuota?, error: String?): String {
    if (error != null) return "error"
    if (quota == null) return "loading..."
    val state = miniMaxIndicatorState(quota) ?: return "no data"
    val reset = QuotaUiUtil.formatResetCompact(state.resetsAt)
    val text = "${state.percent}%"
    return if (reset != null) "$text • $reset" else text
}

internal fun miniMaxIndicatorState(quota: MiniMaxQuota): MiniMaxIndicatorState? {
    val windows = listOfNotNull(quota.sessionUsage, quota.weeklyUsage)
    if (windows.isEmpty()) return null
    val exhausted = windows.filter { it.usagePercent >= 100.0 }
    if (exhausted.isNotEmpty()) {
        return MiniMaxIndicatorState(
            percent = 100,
            resetsAt = exhausted.maxByOrNull { it.resetsAt?.toEpochMilliseconds() ?: Long.MIN_VALUE }?.resetsAt,
        )
    }
    val window = windows.first()
    return MiniMaxIndicatorState(
        percent = clampPercent(window.usagePercent.roundToInt()),
        resetsAt = window.resetsAt,
    )
}

internal fun mistralBarDisplayText(quota: MistralQuota?, error: String?): String {
    if (error != null) return "error"
    if (quota == null) return "loading..."
    val usage = mistralDisplayWindow(quota) ?: return "no data"
    val percent = clampPercent(usage.usagePercent.roundToInt())
    val reset = if (isMistralPerMinuteWindow(usage)) null else QuotaUiUtil.formatResetCompact(usage.resetsAt)
    val text = "$percent%"
    return if (reset != null) "$text • $reset" else text
}

internal fun isMistralPerMinuteWindow(window: de.moritzf.quota.mistral.MistralUsageWindow): Boolean {
    val durationMs = window.periodDurationMs ?: return false
    return durationMs in 1..60_000L
}

internal fun mistralDisplayWindow(quota: MistralQuota): de.moritzf.quota.mistral.MistralUsageWindow? {
    return quota.monthlyUsage
        ?: listOfNotNull(quota.tokenUsage, quota.requestUsage).maxByOrNull { it.usagePercent }
}

internal fun kimiBarDisplayText(quota: KimiQuota?, error: String?): String {
    if (error != null) return "error"
    if (quota == null) return "loading..."
    val usage = kimiDisplayWindow(quota) ?: return "no data"

    val percent = clampPercent(usage.usagePercent.roundToInt())
    val reset = QuotaUiUtil.formatResetCompact(usage.resetsAt)
    val text = "$percent%"
    return if (reset != null) "$text • $reset" else text
}

internal fun cursorBarDisplayText(quota: CursorQuota?, error: String?): String {
    if (error != null) return "error"
    if (quota == null) return "loading..."

    val state = cursorIndicatorState(quota) ?: return "no data"

    val reset = QuotaUiUtil.formatResetCompact(state.resetsAt)
    val text = "${state.percent}%"
    return if (reset != null) "$text • $reset" else text
}

internal fun kimiDisplayWindow(quota: KimiQuota): de.moritzf.quota.kimi.KimiUsageWindow? {
    return quota.sessionUsage ?: quota.totalUsage
}

internal fun gitHubBarDisplayText(quota: GitHubQuota?, error: String?): String {
    if (error != null) return "error"
    if (quota == null) return "loading..."
    githubInactiveBarText(quota.subscriptionState)?.let { return it }
    val usage = gitHubDisplayWindow(quota) ?: return "no data"

    val percent = clampPercent(usage.usagePercent.roundToInt())
    val reset = QuotaUiUtil.formatResetCompact(usage.resetsAt)
    val text = "$percent%"
    return if (reset != null) "$text • $reset" else text
}

internal fun gitHubDisplayWindow(quota: GitHubQuota): de.moritzf.quota.github.GitHubUsageWindow? {
    return quota.limitedWindows().firstOrNull()
}

private fun githubInactiveBarText(state: GitHubSubscriptionState): String? {
    return when (state) {
        GitHubSubscriptionState.ACTIVE -> null
        GitHubSubscriptionState.SUBSCRIPTION_ENDED -> "ended"
        GitHubSubscriptionState.NO_ACTIVE_SUBSCRIPTION -> "inactive"
    }
}

internal fun zaiBarDisplayText(quota: ZaiQuota?, error: String?): String {
    if (error != null) return "error"
    if (quota == null) return "loading..."

    val state = zaiIndicatorState(quota) ?: return "no data"

    val reset = QuotaUiUtil.formatResetCompact(state.resetsAt)
    val text = "${state.percent}%"
    return if (reset != null) "$text • $reset" else text
}

internal fun superGrokBarDisplayText(quota: SuperGrokQuota?, error: String?): String {
    if (error != null) return "error"
    if (quota == null) return "loading..."
    val state = superGrokIndicatorState(quota) ?: return "no data"
    val reset = QuotaUiUtil.formatResetCompact(state.resetsAt)
    val text = "${state.percent}%"
    return if (reset != null) "$text • $reset" else text
}

internal fun claudeBarDisplayText(quota: ClaudeQuota?, error: String?): String {
    if (error != null) return "error"
    if (quota == null) return "loading..."
    val state = claudeIndicatorState(quota) ?: return "no data"
    val reset = QuotaUiUtil.formatResetCompact(state.resetsAt)
    val text = "${state.percent}%"
    return if (reset != null) "$text • $reset" else text
}

internal fun openCodeBarDisplayText(quota: OpenCodeQuota?, error: String?): String {
    if (error != null) return "error"
    if (quota == null) return "loading..."

    val state = openCodeIndicatorState(quota) ?: return quota.availableBalance
        ?.let { "$${QuotaUiUtil.formatOpenCodeBalance(it)}" }
        ?: "no data"

    val reset = formatOpenCodeResetTime(state.resetInSec)
    val text = "${state.percent}%"
    return if (reset != null) "$text \u2022 $reset" else text
}

internal fun formatOpenCodeResetTime(resetInSec: Long): String? {
    if (resetInSec <= 0) return null
    return QuotaUiUtil.formatCompactDuration(java.time.Duration.ofSeconds(resetInSec))
}

internal fun indicatorBarDisplayText(quota: OpenAiCodexQuota?, error: String?, loggedIn: Boolean): String {
    if (!loggedIn) {
        return "not logged in"
    }
    if (error != null) {
        return "error"
    }

    val state = indicatorQuotaState(quota) ?: return "loading..."

    val display = openAiIndicatorDisplayState(quota, state) ?: return "available"
    display.creditsBalanceLabel?.let { return it }
    val reset = QuotaUiUtil.formatResetCompact(display.resetsAt)
    val text = "${display.percent}%"
    return if (reset != null) "$text \u2022 $reset" else text
}

internal fun indicatorDisplayPercent(quota: OpenAiCodexQuota?, error: String?, loggedIn: Boolean): Int {
    if (!loggedIn || error != null) {
        return -1
    }

    val state = indicatorQuotaState(quota) ?: return -1
    return openAiIndicatorDisplayState(quota, state)?.percent ?: -1
}

internal fun openAiIndicatorDisplayState(quota: OpenAiCodexQuota?, state: IndicatorQuotaState): IndicatorDisplayState? {
    if (state.limitReached) {
        return IndicatorDisplayState(
            percent = 100,
            resetsAt = limitingWindow(quota, state.kind)?.resetsAt,
        )
    }

    quota?.credits?.balance?.toDoubleOrNull()?.takeIf { quota.credits?.hasCredits == true }?.let { balance ->
        return IndicatorDisplayState(
            percent = 0,
            resetsAt = null,
            creditsBalanceLabel = formatCreditsBalanceLabel(balance),
        )
    }

    val window = state.window ?: return null
    val percent = clampPercent(window.usedPercent.roundToInt())
    if (percent >= 100) {
        return IndicatorDisplayState(
            percent = 100,
            resetsAt = limitingWindow(quota, state.kind)?.resetsAt,
        )
    }

    return IndicatorDisplayState(
        percent = percent,
        resetsAt = window.resetsAt,
    )
}

internal fun openCodeIndicatorState(quota: OpenCodeQuota): OpenCodeIndicatorState? {
    val windows = listOfNotNull(quota.rollingUsage, quota.weeklyUsage, quota.monthlyUsage)
    if (windows.isEmpty()) return null

    val exhausted = windows.filter { it.isExhausted() }
    if (exhausted.isNotEmpty()) {
        return OpenCodeIndicatorState(
            percent = 100,
            resetInSec = exhausted.maxOf { it.resetInSec },
        )
    }

    val window = windows.first()
    return OpenCodeIndicatorState(
        percent = clampPercent(window.usagePercent.roundToInt()),
        resetInSec = window.resetInSec,
    )
}

private fun OpenCodeUsageWindow.isExhausted(): Boolean {
    return isRateLimited || usagePercent >= 100
}

internal data class CursorIndicatorState(
    val percent: Int,
    val resetsAt: Instant?,
)

internal fun cursorIndicatorState(quota: CursorQuota): CursorIndicatorState? {
    val planUsage = quota.planUsage
    val percent = quota.primaryUsagePercent() ?: return null
    val resetsAt = planUsage?.billingCycleEnd
    if (percent >= 100.0) {
        return CursorIndicatorState(percent = 100, resetsAt = resetsAt)
    }
    return CursorIndicatorState(
        percent = clampPercent(percent.roundToInt()),
        resetsAt = resetsAt,
    )
}

internal fun ollamaIndicatorState(quota: de.moritzf.quota.ollama.OllamaQuota): OllamaIndicatorState? {
    val windows = listOfNotNull(
        quota.sessionUsage?.let { it to QuotaPeriodDurations.ROLLING_5H },
        quota.weeklyUsage?.let { it to QuotaPeriodDurations.WEEKLY },
        quota.monthlyUsage?.let { it to QuotaPeriodDurations.MONTHLY },
    )
    if (windows.isEmpty()) return null

    val exhausted = windows.filter { (window, _) -> window.usagePercent >= 100.0 }
    if (exhausted.isNotEmpty()) {
        val (window, period) = exhausted.maxBy { (window, _) ->
            window.resetsAt?.toEpochMilliseconds() ?: Long.MIN_VALUE
        }
        return OllamaIndicatorState(
            percent = 100,
            resetsAt = window.resetsAt,
            period = period,
        )
    }

    val (window, period) = windows.first()
    return OllamaIndicatorState(
        percent = clampPercent(window.usagePercent.roundToInt()),
        resetsAt = window.resetsAt,
        period = period,
    )
}

/**
 * Formats `42% • 1h` when [resetsAt] is known. When the provider omits reset time, falls back to the
 * known window length as `42% (5h)` so the UI never shows a fake "unknown".
 */
internal fun formatPercentWithOptionalTime(
    percent: Int,
    resetsAt: Instant?,
    fallbackPeriod: java.time.Duration? = null,
): String {
    val base = "${clampPercent(percent)}%"
    val reset = QuotaUiUtil.formatResetCompact(resetsAt)
    if (reset != null) return "$base • $reset"
    val window = fallbackPeriod?.let { QuotaUiUtil.formatCompactDuration(it) } ?: return base
    return "$base ($window)"
}

internal fun zaiIndicatorState(quota: ZaiQuota): ZaiIndicatorState? {
    val windows = listOfNotNull(quota.sessionUsage, quota.weeklyUsage)
    if (windows.isEmpty()) {
        return quota.webSearchUsage?.let {
            ZaiIndicatorState(
                percent = clampPercent(it.usagePercent.roundToInt()),
                resetsAt = it.resetsAt,
            )
        }
    }

    val exhausted = windows.filter { it.usagePercent >= 100.0 }
    if (exhausted.isNotEmpty()) {
        return ZaiIndicatorState(
            percent = 100,
            resetsAt = exhausted.maxByOrNull { it.resetsAt?.toEpochMilliseconds() ?: Long.MIN_VALUE }?.resetsAt,
        )
    }

    val window = windows.first()
    return ZaiIndicatorState(
        percent = clampPercent(window.usagePercent.roundToInt()),
        resetsAt = window.resetsAt,
    )
}

internal fun superGrokIndicatorState(quota: SuperGrokQuota): SuperGrokIndicatorState? {
    val window = quota.creditUsage ?: return null
    val percent = if (window.isExhausted()) {
        100
    } else {
        clampPercent(window.usagePercent.roundToInt())
    }
    return SuperGrokIndicatorState(percent, window.resetsAt)
}

internal fun claudeIndicatorState(quota: ClaudeQuota): ClaudeIndicatorState? {
    val window = quota.primaryWindow()
    if (window != null) {
        val percent = if (window.usagePercent >= 100.0) {
            100
        } else {
            clampPercent(window.usagePercent.roundToInt())
        }
        return ClaudeIndicatorState(percent, window.resetsAt)
    }
    val extra = quota.extraUsage?.takeIf { it.isEnabled } ?: return null
    val percent = extra.usagePercent?.let {
        if (it >= 100.0) 100 else clampPercent(it.roundToInt())
    } ?: return null
    return ClaudeIndicatorState(percent, null)
}

internal fun indicatorQuotaState(quota: OpenAiCodexQuota?): IndicatorQuotaState? {
    if (quota == null) {
        return null
    }

    val codexWindow = shortestUsageWindow(listOfNotNull(quota.primary, quota.secondary))
    if (codexWindow != null) {
        return IndicatorQuotaState(
            kind = IndicatorQuotaKind.CODEX,
            window = codexWindow,
            limitReached = quota.limitReached == true || codexWindow.usedPercent >= 100.0,
        )
    }

    val reviewWindow = shortestUsageWindow(listOfNotNull(quota.reviewPrimary, quota.reviewSecondary))
    if (quota.limitReached == true) {
        return IndicatorQuotaState(
            kind = IndicatorQuotaKind.CODEX,
            window = null,
            limitReached = true,
        )
    }

    if (reviewWindow != null) {
        return IndicatorQuotaState(
            kind = IndicatorQuotaKind.REVIEW,
            window = reviewWindow,
            limitReached = quota.reviewLimitReached == true || reviewWindow.usedPercent >= 100.0,
        )
    }

    if (quota.reviewLimitReached == true) {
        return IndicatorQuotaState(
            kind = IndicatorQuotaKind.REVIEW,
            window = null,
            limitReached = true,
        )
    }

    if (quota.limitReached != null) {
        return IndicatorQuotaState(
            kind = IndicatorQuotaKind.CODEX,
            window = null,
            limitReached = quota.limitReached == true,
        )
    }

    if (quota.reviewLimitReached != null) {
        return IndicatorQuotaState(
            kind = IndicatorQuotaKind.REVIEW,
            window = null,
            limitReached = quota.reviewLimitReached == true,
        )
    }

    if (quota.isCreditsDepleted()) {
        return IndicatorQuotaState(
            kind = IndicatorQuotaKind.CODEX,
            window = null,
            limitReached = true,
        )
    }

    if (quota.isAssignedCreditsQuota() && (quota.credits?.hasCredits == true || quota.credits?.unlimited == true)) {
        return IndicatorQuotaState(
            kind = IndicatorQuotaKind.CODEX,
            window = null,
            limitReached = false,
        )
    }

    return null
}

private fun formatCreditsBalanceLabel(balance: Double): String {
    return if (balance >= 100.0) {
        "$${balance.roundToInt()}"
    } else {
        "$${String.format(Locale.US, "%.2f", balance)}"
    }
}

internal fun clampPercent(value: Int): Int = value.coerceIn(0, 100)

private fun shortestUsageWindow(windows: List<UsageWindow>): UsageWindow? {
    if (windows.isEmpty()) return null
    return windows.minByOrNull { it.windowDuration?.toMinutes() ?: Long.MAX_VALUE }
        ?: windows.first()
}

/**
 * Returns the window that is at 100% usage and has the latest reset time.
 * Falls back to whichever window resets latest if none are explicitly at 100%.
 */
internal fun limitingWindow(
    quota: OpenAiCodexQuota?,
    kind: IndicatorQuotaKind = IndicatorQuotaKind.CODEX,
): UsageWindow? {
    val windows = when (kind) {
        IndicatorQuotaKind.CODEX -> listOfNotNull(quota?.primary, quota?.secondary)
        IndicatorQuotaKind.REVIEW -> listOfNotNull(quota?.reviewPrimary, quota?.reviewSecondary)
    }
    if (windows.isEmpty()) return null
    return windows
        .filter { it.usedPercent >= 100.0 }
        .maxByOrNull { it.resetsAt?.toEpochMilliseconds() ?: Long.MIN_VALUE }
        ?: windows.maxByOrNull { it.resetsAt?.toEpochMilliseconds() ?: Long.MIN_VALUE }
}

internal fun scaleIconToQuotaStatusSize(icon: Icon, component: JComponent): Icon {
    val statusIcon = QuotaIcons.STATUS
    val targetWidth = statusIcon.iconWidth
    val targetHeight = statusIcon.iconHeight
    val iconWidth = icon.iconWidth
    val iconHeight = icon.iconHeight
    if (iconWidth <= 0 || iconHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
        return icon
    }
    if (iconWidth <= targetWidth && iconHeight <= targetHeight) {
        return icon
    }

    val widthScale = targetWidth / iconWidth.toFloat()
    val heightScale = targetHeight / iconHeight.toFloat()
    return IconUtil.scale(icon, component, minOf(widthScale, heightScale))
}
