package de.moritzf.quota.idea.ui.popup

import com.intellij.util.ui.JBUI
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.idea.ui.indicator.clampPercent
import de.moritzf.quota.kimi.KimiQuota
import de.moritzf.quota.kimi.KimiUsageWindow
import de.moritzf.quota.shared.ProviderQuota
import kotlin.math.roundToInt

internal class KimiPopupSection : ProviderPopupSection() {
    private val separator = createSeparatedBlock()
    private val errorLabel = createWarningLabel("").apply { border = JBUI.Borders.emptyTop(1) }
    private val titleLabel =
        createSectionTitleLabel("Kimi Code", QuotaIcons.KIMI).apply {
            border = JBUI.Borders.emptyTop(0)
        }
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

    override fun update(quota: ProviderQuota?, error: String?, visible: Boolean) {
        updateContent(quota as? KimiQuota, error, visible)
    }

    private fun updateContent(quota: KimiQuota?, error: String?, visible: Boolean) {
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
                titleLabel.text = sectionTitle("Kimi Code")
                sessionBlock.showLoading("Session")
                overallBlock.showLoading("Overall")
            }

            else -> {
                val limitReached =
                    (quota.sessionUsage?.usagePercent ?: 0.0) >= 100.0 ||
                        (quota.totalUsage?.usagePercent ?: 0.0) >= 100.0
                errorLabel.isVisible = limitReached
                if (limitReached) {
                    errorLabel.text = "Kimi limit reached"
                }

                titleLabel.isVisible = true
                titleLabel.text = sectionTitle("Kimi Code", quota.plan.takeIf { it.isNotBlank() })
                quota.sessionUsage?.let { sessionBlock.updateKimi(it, "Session") }
                    ?: sessionBlock.clear()
                quota.totalUsage?.let { overallBlock.updateKimi(it, "Overall") }
                    ?: overallBlock.clear()
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
        var info = "$percent% used"
        if (resetText != null) info += " - $resetText"
        update(describeDurationLimitLabel(window.periodDuration, label), info, percent)
    }
}
