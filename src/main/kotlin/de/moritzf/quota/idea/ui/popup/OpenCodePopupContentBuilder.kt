package de.moritzf.quota.idea.ui.popup

import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.idea.ui.indicator.clampPercent
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.opencode.OpenCodeUsageWindow
import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import javax.swing.JPanel

private const val OPENCODE_GO_LABEL = "OpenCode Go"

internal class OpenCodePopupSection : JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 0, true, false)) {
    private val separator = createSeparatedBlock()
    private val errorLabel = createWarningLabel("").apply { border = JBUI.Borders.emptyTop(1) }
    private val titleLabel = createSectionTitleLabel(OPENCODE_GO_LABEL, QuotaIcons.OPENCODE).apply { border = JBUI.Borders.emptyTop(0) }
    private val balanceLabel = createMutedLabel("").apply { border = JBUI.Borders.emptyTop(2) }
    private val rollingBlock = WindowBlockPanel(3)
    private val weeklyBlock = WindowBlockPanel(5)
    private val monthlyBlock = WindowBlockPanel(5)

    init {
        isOpaque = false
        add(separator)
        add(errorLabel)
        add(titleLabel)
        add(balanceLabel)
        add(rollingBlock)
        add(weeklyBlock)
        add(monthlyBlock)
        hideAll()
    }

    fun update(quota: OpenCodeQuota?, error: String?, visible: Boolean) {
        isVisible = visible
        if (!visible) return

        when {
            error != null -> {
                errorLabel.isVisible = true
                errorLabel.text = "OpenCode error: $error"
                hideContent()
            }
            quota == null -> {
                hideAll()
                titleLabel.isVisible = true
                titleLabel.text = OPENCODE_GO_LABEL
                rollingBlock.showLoading("5h rolling")
                weeklyBlock.showLoading("Weekly")
                monthlyBlock.showLoading("Monthly")
            }
            else -> {
                errorLabel.isVisible = false
                titleLabel.isVisible = true
                titleLabel.text = OPENCODE_GO_LABEL
                val balanceText = quota.availableBalance?.let(QuotaUiUtil::formatOpenCodeBalance)
                balanceLabel.isVisible = balanceText != null
                if (balanceText != null) {
                    balanceLabel.text = "Available balance: $$balanceText"
                }
                quota.rollingUsage?.let { rollingBlock.updateOpenCode(it, "5h rolling") } ?: rollingBlock.clear()
                quota.weeklyUsage?.let { weeklyBlock.updateOpenCode(it, "Weekly") } ?: weeklyBlock.clear()
                quota.monthlyUsage?.let { monthlyBlock.updateOpenCode(it, "Monthly") } ?: monthlyBlock.clear()
            }
        }
    }

    private fun hideAll() {
        errorLabel.isVisible = false
        hideContent()
    }

    private fun hideContent() {
        titleLabel.isVisible = false
        balanceLabel.isVisible = false
        rollingBlock.isVisible = false
        weeklyBlock.isVisible = false
        monthlyBlock.isVisible = false
    }

    private fun WindowBlockPanel.updateOpenCode(window: OpenCodeUsageWindow, label: String) {
        val percent = clampPercent(window.usagePercent)
        val resetText = QuotaUiUtil.formatResetInSeconds(window.resetInSec)
        var info = "$percent% used"
        if (window.isRateLimited) info += " - LIMIT REACHED"
        if (resetText != null) info += " - $resetText"
        update("$label limit", info, percent)
    }
}
