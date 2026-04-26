package de.moritzf.quota.idea.ui.indicator

import com.intellij.ui.components.JBLabel
import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.settings.QuotaDisplayMode
import de.moritzf.quota.idea.ui.QuotaUiUtil
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.openai.UsageWindow
import java.awt.Component
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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
    val allowed: Boolean?,
)

internal class QuotaIndicatorComponent(
    horizontalPadding: Int,
    private val onClick: (Component, QuotaIndicatorData) -> Unit,
) : BorderLayoutPanel() {
    private val statusIconLabel = createStatusIconLabel()
    private val sourceIconLabel = createSourceIconLabel()
    private val percentageComponent = QuotaPercentageIndicator()
    private var data: QuotaIndicatorData = QuotaIndicatorData.OpenAi(quota = null, error = null)
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
        val tooltip = when (data) {
            is QuotaIndicatorData.OpenAi -> buildQuotaTooltipText(data.quota, data.error)
            is QuotaIndicatorData.OpenCode -> buildOpenCodeTooltipText(data.quota, data.error)
        }
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
                if (icon != null) {
                    sourceIconLabel.icon = scaledSourceIcon(icon, sourceIconLabel)
                    showPercentageContent(sourceIconLabel, percentageComponent)
                } else {
                    showContent(percentageComponent)
                }
            }
        }

        revalidate()
        repaint()
    }

    override fun getToolTipText(event: MouseEvent?): String {
        return when (val currentData = data) {
            is QuotaIndicatorData.OpenAi -> buildQuotaTooltipText(currentData.quota, currentData.error)
            is QuotaIndicatorData.OpenCode -> buildOpenCodeTooltipText(currentData.quota, currentData.error)
        }
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

    private fun resolveSourceIcon(): Icon? {
        return when (data) {
            is QuotaIndicatorData.OpenAi -> QuotaIcons.OPENAI
            is QuotaIndicatorData.OpenCode -> QuotaIcons.OPENCODE
        }
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
        return when (val currentData = data) {
            is QuotaIndicatorData.OpenAi -> {
                val authService = QuotaAuthService.getInstance()
                indicatorBarDisplayText(currentData.quota, currentData.error, authService.isLoggedIn())
            }
            is QuotaIndicatorData.OpenCode -> openCodeBarDisplayText(currentData.quota, currentData.error)
        }
    }

    private fun cakeIcon(): Icon {
        val currentData = data
        if (currentData is QuotaIndicatorData.OpenCode) {
            return currentData.quota?.let(::openCodeCakeIcon) ?: QuotaIcons.CAKE_UNKNOWN
        }
        val openAiData = currentData as QuotaIndicatorData.OpenAi
        val authService = QuotaAuthService.getInstance()
        if (!authService.isLoggedIn() || openAiData.error != null) {
            return QuotaIcons.CAKE_UNKNOWN
        }

        val state = indicatorQuotaState(openAiData.quota) ?: return QuotaIcons.CAKE_UNKNOWN
        if (state.limitReached) {
            return QuotaIcons.CAKE_100
        }
        if (state.allowed == false) {
            return QuotaIcons.CAKE_UNKNOWN
        }

        val percent = state.window?.let { clampPercent(it.usedPercent.roundToInt()) } ?: return QuotaIcons.CAKE_UNKNOWN
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

    private fun openCodeCakeIcon(quota: de.moritzf.quota.opencode.OpenCodeQuota): Icon {
        val window = quota.rollingUsage ?: quota.weeklyUsage ?: quota.monthlyUsage
        if (window == null || window.isRateLimited) {
            return QuotaIcons.CAKE_UNKNOWN
        }
        val percent = window.usagePercent
        return when {
            percent >= 100 -> QuotaIcons.CAKE_100
            percent <= 0 -> QuotaIcons.CAKE_0
            else -> {
                val bucket = minOf(95, ((percent + 4) / 5) * 5)
                when (bucket) {
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
        val currentData = data
        if (currentData is QuotaIndicatorData.OpenCode) {
            val window = currentData.quota?.let { it.rollingUsage ?: it.weeklyUsage ?: it.monthlyUsage }
            return window?.usagePercent ?: -1
        }
        val openAiData = currentData as QuotaIndicatorData.OpenAi
        val authService = QuotaAuthService.getInstance()
        return indicatorDisplayPercent(openAiData.quota, openAiData.error, authService.isLoggedIn())
    }

    private fun updatePercentageDisplay() {
        val percentage = displayPercent()
        val text = barDisplayText()
        if (percentage >= 0) {
            percentageComponent.update(
                text = text,
                fraction = percentage / 100.0,
                fillColor = QuotaUsageColors.usageColor(percentage),
            )
        } else {
            percentageComponent.update(
                text = text,
                fraction = 0.0,
                fillColor = QuotaUsageColors.GRAY,
            )
        }
    }
}

internal fun buildQuotaTooltipText(quota: OpenAiCodexQuota?, error: String?): String {
    val authService = QuotaAuthService.getInstance()
    return buildQuotaTooltipText(quota, error, authService.isLoggedIn())
}

internal fun buildOpenCodeTooltipText(quota: OpenCodeQuota?, error: String?): String {
    if (error != null) {
        return "OpenCode quota: $error"
    }
    if (quota == null) {
        return "OpenCode quota: loading"
    }
    val window = quota.rollingUsage ?: quota.weeklyUsage ?: quota.monthlyUsage
    val availableBalance = quota.availableBalance
    return when {
        window == null -> "OpenCode quota: no usage data"
        window.isRateLimited -> "OpenCode quota: rate limited"
        availableBalance != null ->
            "OpenCode quota: ${window.usagePercent}% used, balance $${QuotaUiUtil.formatOpenCodeBalance(availableBalance)}"
        else -> "OpenCode quota: ${window.usagePercent}% used"
    }
}

internal fun openCodeBarDisplayText(quota: OpenCodeQuota?, error: String?): String {
    if (error != null) return "error"
    if (quota == null) return "loading..."

    val window = quota.rollingUsage ?: quota.weeklyUsage ?: quota.monthlyUsage
        val balanceText = quota.availableBalance?.let { "$${QuotaUiUtil.formatOpenCodeBalance(it)}" }
    return when {
        window == null -> if (balanceText != null) "no data \u2022 $balanceText" else "no data"
        window.isRateLimited -> if (balanceText != null) "rate limited \u2022 $balanceText" else "rate limited"
        else -> {
            val percent = window.usagePercent
            val reset = formatOpenCodeResetTime(window.resetInSec)
            val parts = mutableListOf("$percent%")
            if (balanceText != null) parts.add(balanceText)
            if (reset != null) parts.add(reset)
            parts.joinToString(" \u2022 ")
        }
    }
}

private fun formatOpenCodeResetTime(resetInSec: Long): String? {
    if (resetInSec <= 0) return null
    return when {
        resetInSec < 60 -> "${resetInSec}s"
        resetInSec < 3600 -> "${resetInSec / 60}m"
        resetInSec < 86400 -> "${resetInSec / 3600}h"
        else -> "${resetInSec / 86400}d"
    }
}

internal fun buildQuotaTooltipText(quota: OpenAiCodexQuota?, error: String?, loggedIn: Boolean): String {
    if (!loggedIn) {
        return "OpenAI usage quota: not logged in"
    }
    if (error != null) {
        return "OpenAI usage quota: $error"
    }

    val state = indicatorQuotaState(quota) ?: return "OpenAI usage quota: loading"
    val label = when (state.kind) {
        IndicatorQuotaKind.CODEX -> "OpenAI usage quota"
        IndicatorQuotaKind.REVIEW -> "OpenAI code review quota"
    }
    return when {
        state.limitReached -> "$label: limit reached"
        state.allowed == false -> "$label: usage not allowed"
        state.window == null -> "$label: available"
        else -> {
            val percent = clampPercent(state.window.usedPercent.roundToInt())
            "$label: $percent% used"
        }
    }
}

internal fun indicatorBarDisplayText(quota: OpenAiCodexQuota?, error: String?, loggedIn: Boolean): String {
    if (!loggedIn) {
        return "not logged in"
    }
    if (error != null) {
        return "error"
    }

    val state = indicatorQuotaState(quota) ?: return "loading..."
    return when {
        state.limitReached -> {
            val resetWindow = limitingWindow(quota, state.kind)
            val reset = QuotaUiUtil.formatResetCompact(resetWindow?.resetsAt)
            if (reset != null) "100% • $reset" else "100%"
        }

        state.allowed == false -> {
            "not allowed"
        }

        state.window == null -> {
            "available"
        }

        else -> {
            val percent = clampPercent(state.window.usedPercent.roundToInt())
            val reset = QuotaUiUtil.formatResetCompact(state.window.resetsAt)
            val text = "$percent%"
            if (reset != null) "$text • $reset" else text
        }
    }
}

internal fun indicatorDisplayPercent(quota: OpenAiCodexQuota?, error: String?, loggedIn: Boolean): Int {
    if (!loggedIn || error != null) {
        return -1
    }

    val state = indicatorQuotaState(quota) ?: return -1
    if (state.limitReached) return 100
    if (state.allowed == false) return -1
    val window = state.window ?: return -1
    return clampPercent(window.usedPercent.roundToInt())
}

internal fun indicatorQuotaState(quota: OpenAiCodexQuota?): IndicatorQuotaState? {
    if (quota == null) {
        return null
    }

    val codexWindow = quota.primary ?: quota.secondary
    if (codexWindow != null) {
        return IndicatorQuotaState(
            kind = IndicatorQuotaKind.CODEX,
            window = codexWindow,
            limitReached = quota.limitReached == true,
            allowed = quota.allowed,
        )
    }

    val reviewWindow = quota.reviewPrimary ?: quota.reviewSecondary
    if (isBlockedState(quota.limitReached, quota.allowed)) {
        return IndicatorQuotaState(
            kind = IndicatorQuotaKind.CODEX,
            window = null,
            limitReached = quota.limitReached == true,
            allowed = quota.allowed,
        )
    }

    if (reviewWindow != null) {
        return IndicatorQuotaState(
            kind = IndicatorQuotaKind.REVIEW,
            window = reviewWindow,
            limitReached = quota.reviewLimitReached == true,
            allowed = quota.reviewAllowed,
        )
    }

    if (isBlockedState(quota.reviewLimitReached, quota.reviewAllowed)) {
        return IndicatorQuotaState(
            kind = IndicatorQuotaKind.REVIEW,
            window = null,
            limitReached = quota.reviewLimitReached == true,
            allowed = quota.reviewAllowed,
        )
    }

    if (hasAnyState(quota.limitReached, quota.allowed)) {
        return IndicatorQuotaState(
            kind = IndicatorQuotaKind.CODEX,
            window = null,
            limitReached = quota.limitReached == true,
            allowed = quota.allowed,
        )
    }

    if (hasAnyState(quota.reviewLimitReached, quota.reviewAllowed)) {
        return IndicatorQuotaState(
            kind = IndicatorQuotaKind.REVIEW,
            window = null,
            limitReached = quota.reviewLimitReached == true,
            allowed = quota.reviewAllowed,
        )
    }

    return null
}

internal fun clampPercent(value: Int): Int = value.coerceIn(0, 100)

private fun isBlockedState(limitReached: Boolean?, allowed: Boolean?): Boolean {
    return limitReached == true || allowed == false
}

private fun hasAnyState(limitReached: Boolean?, allowed: Boolean?): Boolean {
    return limitReached != null || allowed != null
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
