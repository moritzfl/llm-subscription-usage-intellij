package de.moritzf.quota.idea

import com.intellij.ui.components.JBLabel
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.quota.OpenAiCodexQuota
import de.moritzf.quota.UsageWindow
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
    private val onClick: (Component, OpenAiCodexQuota?, String?) -> Unit,
) : BorderLayoutPanel() {
    private val statusIconLabel = createStatusIconLabel()
    private val percentageComponent = QuotaPercentageIndicator()
    private var quota: OpenAiCodexQuota? = null
    private var error: String? = null
    private val clickListener = object : MouseAdapter() {
        override fun mouseClicked(event: MouseEvent) {
            onClick(this@QuotaIndicatorComponent, quota, error)
        }
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(0, horizontalPadding)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = " "
        addMouseListener(clickListener)
        statusIconLabel.addMouseListener(clickListener)
        percentageComponent.addMouseListener(clickListener)
        statusIconLabel.cursor = cursor
        percentageComponent.cursor = cursor
    }

    fun updateUsage(quota: OpenAiCodexQuota?, error: String?, displayMode: QuotaDisplayMode) {
        this.quota = quota
        this.error = error
        val tooltip = buildQuotaTooltipText(quota, error)
        toolTipText = tooltip
        statusIconLabel.toolTipText = tooltip
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
                showContent(percentageComponent)
            }
        }

        revalidate()
        repaint()
    }

    override fun getToolTipText(event: MouseEvent?): String = buildQuotaTooltipText(quota, error)

    private fun showContent(component: JComponent) {
        removeAll()
        addToCenter(component)
    }

    private fun createStatusIconLabel(): JBLabel {
        return JBLabel().apply {
            horizontalAlignment = SwingConstants.CENTER
            verticalAlignment = SwingConstants.CENTER
        }
    }

    private fun barDisplayText(): String {
        val authService = QuotaAuthService.getInstance()
        return indicatorBarDisplayText(quota, error, authService.isLoggedIn())
    }

    private fun cakeIcon(): Icon {
        val authService = QuotaAuthService.getInstance()
        if (!authService.isLoggedIn() || error != null) {
            return QuotaIcons.CAKE_UNKNOWN
        }

        val state = indicatorQuotaState(quota) ?: return QuotaIcons.CAKE_UNKNOWN
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

    private fun scaledCakeIcon(component: JComponent): Icon {
        return scaleIconToQuotaStatusSize(cakeIcon(), component)
    }

    private fun displayPercent(): Int {
        val authService = QuotaAuthService.getInstance()
        return indicatorDisplayPercent(quota, error, authService.isLoggedIn())
    }

    private fun updatePercentageDisplay() {
        val percentage = displayPercent()
        if (percentage >= 0) {
            percentageComponent.update(
                text = barDisplayText(),
                fraction = percentage / 100.0,
                fillColor = QuotaUsageColors.usageColor(percentage),
            )
        } else {
            percentageComponent.update(
                text = barDisplayText(),
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
        return "OpenAI: not logged in"
    }
    if (error != null) {
        return "OpenAI: error"
    }

    val state = indicatorQuotaState(quota) ?: return "OpenAI: loading..."
    return when {
        state.limitReached -> {
            val resetWindow = limitingWindow(quota, state.kind)
            val reset = QuotaUiUtil.formatResetCompact(resetWindow?.resetsAt)
            val prefix = when (state.kind) {
                IndicatorQuotaKind.CODEX -> ""
                IndicatorQuotaKind.REVIEW -> "Review "
            }
            if (reset != null) "${prefix}100% • $reset" else "${prefix}100%"
        }

        state.allowed == false -> {
            when (state.kind) {
                IndicatorQuotaKind.CODEX -> "OpenAI: not allowed"
                IndicatorQuotaKind.REVIEW -> "Review: not allowed"
            }
        }

        state.window == null -> {
            when (state.kind) {
                IndicatorQuotaKind.CODEX -> "OpenAI: available"
                IndicatorQuotaKind.REVIEW -> "Review: available"
            }
        }

        else -> {
            val percent = clampPercent(state.window.usedPercent.roundToInt())
            val reset = QuotaUiUtil.formatResetCompact(state.window.resetsAt)
            val prefix = when (state.kind) {
                IndicatorQuotaKind.CODEX -> ""
                IndicatorQuotaKind.REVIEW -> "Review "
            }
            val text = "$prefix$percent%"
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
