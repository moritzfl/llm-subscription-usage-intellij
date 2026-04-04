package de.moritzf.quota.idea

import com.intellij.ui.components.JBLabel
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.quota.OpenAiCodexQuota
import java.awt.Component
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.SwingConstants
import kotlin.math.roundToInt

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
        if (!authService.isLoggedIn()) {
            return "OpenAI: not logged in"
        }
        if (error != null) {
            return "OpenAI: error"
        }

        val primary = quota?.primary ?: return "OpenAI: loading..."
        val percent = clampPercent(primary.usedPercent.roundToInt())
        val reset = QuotaUiUtil.formatResetCompact(primary.resetsAt)
        return if (reset != null) "$percent% • $reset" else "$percent%"
    }

    private fun cakeIcon(): Icon {
        val authService = QuotaAuthService.getInstance()
        if (!authService.isLoggedIn() || error != null) {
            return QuotaIcons.CAKE_UNKNOWN
        }

        val primary = quota?.primary ?: return QuotaIcons.CAKE_UNKNOWN
        if (quota?.limitReached == true) {
            return QuotaIcons.CAKE_100
        }

        val percent = clampPercent(primary.usedPercent.roundToInt())
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
        if (!authService.isLoggedIn() || error != null) {
            return -1
        }

        val primary = quota?.primary ?: return -1
        return clampPercent(primary.usedPercent.roundToInt())
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
    if (!authService.isLoggedIn()) {
        return "OpenAI usage quota: not logged in"
    }
    if (error != null) {
        return "OpenAI usage quota: $error"
    }

    val primary = quota?.primary ?: return "OpenAI usage quota: loading"
    return "OpenAI usage quota: ${clampPercent(primary.usedPercent.roundToInt())}% used"
}

internal fun clampPercent(value: Int): Int = value.coerceIn(0, 100)

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
