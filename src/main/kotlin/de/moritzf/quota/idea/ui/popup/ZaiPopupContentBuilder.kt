package de.moritzf.quota.idea.ui.popup

import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.idea.ui.indicator.clampPercent
import de.moritzf.quota.zai.ZaiCountUsageWindow
import kotlin.math.roundToInt
import de.moritzf.quota.zai.ZaiQuota
import de.moritzf.quota.zai.ZaiUsageWindow
import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.util.ui.JBUI
import javax.swing.JPanel

private const val ZAI_LABEL = "Z.ai"

internal class ZaiPopupSection : JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 0, true, false)) {
    private val separator = createSeparatedBlock()
    private val errorLabel = createWarningLabel("").apply { border = JBUI.Borders.emptyTop(1) }
    private val titleLabel = createSectionTitleLabel(ZAI_LABEL, QuotaIcons.ZAI).apply { border = JBUI.Borders.emptyTop(0) }
    private val sessionBlock = WindowBlockPanel(3)
    private val weeklyBlock = WindowBlockPanel(5)
    private val webSearchBlock = WindowBlockPanel(5)

    init {
        isOpaque = false
        add(separator)
        add(errorLabel)
        add(titleLabel)
        add(sessionBlock)
        add(weeklyBlock)
        add(webSearchBlock)
        hideAll()
    }

    fun update(quota: ZaiQuota?, error: String?, visible: Boolean) {
        isVisible = visible
        if (!visible) return

        when {
            error != null -> {
                errorLabel.isVisible = true
                errorLabel.text = "Z.ai error: $error"
                hideContent()
            }
            quota == null -> {
                hideAll()
                titleLabel.isVisible = true
                titleLabel.text = ZAI_LABEL
                sessionBlock.showLoading("Session")
                weeklyBlock.showLoading("Weekly")
                webSearchBlock.showLoading("Web searches")
            }
            else -> {
                errorLabel.isVisible = false
                val label = quota.plan.takeIf { it.isNotBlank() }?.let { "$ZAI_LABEL ($it)" } ?: ZAI_LABEL
                titleLabel.isVisible = true
                titleLabel.text = label
                quota.sessionUsage?.let { sessionBlock.updateZai(it, "Session") } ?: sessionBlock.clear()
                quota.weeklyUsage?.let { weeklyBlock.updateZai(it, "Weekly") } ?: weeklyBlock.clear()
                quota.webSearchUsage?.let { webSearchBlock.updateZaiCount(it, "Web searches") } ?: webSearchBlock.clear()
            }
        }
    }

    private fun hideAll() {
        errorLabel.isVisible = false
        hideContent()
    }

    private fun hideContent() {
        titleLabel.isVisible = false
        sessionBlock.isVisible = false
        weeklyBlock.isVisible = false
        webSearchBlock.isVisible = false
    }

    private fun WindowBlockPanel.updateZai(window: ZaiUsageWindow, label: String) {
        val percent = clampPercent(window.usagePercent.roundToInt())
        val resetText = QuotaUiUtil.formatReset(window.resetsAt)
        var info = "$percent% used"
        if (resetText != null) info += " - $resetText"
        update("$label limit", info, percent)
    }

    private fun WindowBlockPanel.updateZaiCount(window: ZaiCountUsageWindow, label: String) {
        val percent = clampPercent(window.usagePercent.roundToInt())
        val resetText = QuotaUiUtil.formatReset(window.resetsAt)
        var info = "${window.used}/${window.limit} used"
        if (resetText != null) info += " - $resetText"
        update(label, info, percent)
    }
}
