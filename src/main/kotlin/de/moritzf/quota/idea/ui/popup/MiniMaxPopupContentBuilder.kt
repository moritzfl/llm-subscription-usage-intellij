package de.moritzf.quota.idea.ui.popup

import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.idea.ui.indicator.clampPercent
import de.moritzf.quota.minimax.MiniMaxQuota
import kotlin.math.roundToInt
import de.moritzf.quota.minimax.MiniMaxUsageWindow
import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.util.ui.JBUI
import javax.swing.JPanel

internal class MiniMaxPopupSection : JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 0, true, false)) {
    private val separator = createSeparatedBlock()
    private val errorLabel = createWarningLabel("").apply { border = JBUI.Borders.emptyTop(1) }
    private val titleLabel = createSectionTitleLabel("MiniMax", QuotaIcons.MINIMAX).apply { border = JBUI.Borders.emptyTop(0) }
    private val sessionBlock = WindowBlockPanel(3)

    init {
        isOpaque = false
        add(separator)
        add(errorLabel)
        add(titleLabel)
        add(sessionBlock)
        hideAll()
    }

    fun update(quota: MiniMaxQuota?, error: String?, visible: Boolean) {
        isVisible = visible
        if (!visible) return

        when {
            error != null -> {
                errorLabel.isVisible = true
                errorLabel.text = "MiniMax error: $error"
                titleLabel.isVisible = false
                sessionBlock.isVisible = false
            }
            quota == null -> {
                hideAll()
                titleLabel.isVisible = true
                titleLabel.text = "MiniMax"
                sessionBlock.showLoading("Session")
            }
            else -> {
                errorLabel.isVisible = false
                titleLabel.isVisible = true
                titleLabel.text = quota.plan.ifBlank { "MiniMax Coding Plan (${quota.region})" }
                quota.sessionUsage?.let { sessionBlock.updateMiniMax(it, "Session") } ?: sessionBlock.clear()
            }
        }
    }

    private fun hideAll() {
        errorLabel.isVisible = false
        titleLabel.isVisible = false
        sessionBlock.isVisible = false
    }

    private fun WindowBlockPanel.updateMiniMax(window: MiniMaxUsageWindow, label: String) {
        val percent = clampPercent(window.usagePercent.roundToInt())
        val resetText = QuotaUiUtil.formatReset(window.resetsAt)
        var info = "${window.used}/${window.limit} prompts used"
        if (resetText != null) info += " - $resetText"
        update("$label limit", info, percent)
    }
}
