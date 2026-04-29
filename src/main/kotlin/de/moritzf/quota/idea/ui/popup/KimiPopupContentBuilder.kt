package de.moritzf.quota.idea.ui.popup

import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.idea.ui.indicator.clampPercent
import de.moritzf.quota.kimi.KimiQuota
import kotlin.math.roundToInt
import de.moritzf.quota.kimi.KimiUsageWindow
import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.util.ui.JBUI
import javax.swing.JPanel

internal class KimiPopupSection : JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 0, true, false)) {
    private val separator = createSeparatedBlock()
    private val errorLabel = createWarningLabel("").apply { border = JBUI.Borders.emptyTop(1) }
    private val titleLabel =
        createSectionTitleLabel("Kimi Code", QuotaIcons.KIMI).apply { border = JBUI.Borders.emptyTop(0) }
    private val sessionBlock = WindowBlockPanel(3)
    private val overallBlock = WindowBlockPanel(5)

    init {
        isOpaque = false
        add(separator)
        add(errorLabel)
        add(titleLabel)
        add(sessionBlock)
        add(overallBlock)
        hideAll()
    }

    fun update(quota: KimiQuota?, error: String?, visible: Boolean) {
        isVisible = visible
        if (!visible) return

        when {
            error != null -> {
                errorLabel.isVisible = true
                errorLabel.text = "Kimi error: $error"
                titleLabel.isVisible = false
                sessionBlock.isVisible = false
                overallBlock.isVisible = false
            }

            quota == null -> {
                hideAll()
                titleLabel.isVisible = true
                titleLabel.text = "Kimi Code"
                sessionBlock.showLoading("Session")
                overallBlock.showLoading("Overall")
            }

            else -> {
                errorLabel.isVisible = false
                titleLabel.isVisible = true
                titleLabel.text = quota.plan.ifBlank { "Kimi Code" }
                quota.sessionUsage?.let { sessionBlock.updateKimi(it, "Session") } ?: sessionBlock.clear()
                quota.totalUsage?.let { overallBlock.updateKimi(it, "Overall") } ?: overallBlock.clear()
            }
        }
    }

    private fun hideAll() {
        errorLabel.isVisible = false
        titleLabel.isVisible = false
        sessionBlock.isVisible = false
        overallBlock.isVisible = false
    }

    private fun WindowBlockPanel.updateKimi(window: KimiUsageWindow, label: String) {
        val percent = clampPercent(window.usagePercent.roundToInt())
        val resetText = QuotaUiUtil.formatReset(window.resetsAt)
        var info = "${window.used}/${window.limit} used"
        if (resetText != null) info += " - $resetText"
        update("$label limit", info, percent)
    }
}
