package de.moritzf.quota.idea.ui.popup

import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.idea.ui.indicator.QuotaPeriodDurations
import de.moritzf.quota.idea.ui.indicator.clampPercent
import de.moritzf.quota.minimax.MiniMaxQuota
import kotlin.math.roundToInt
import de.moritzf.quota.minimax.MiniMaxUsageWindow
import com.intellij.util.ui.JBUI
import de.moritzf.quota.shared.ProviderQuota

internal class MiniMaxPopupSection : ProviderPopupSection() {
    private val separator = createSeparatedBlock()
    private val errorLabel = createWarningLabel("").apply { border = JBUI.Borders.emptyTop(1) }
    private val titleLabel = createSectionTitleLabel("MiniMax", QuotaIcons.MINIMAX).apply { border = JBUI.Borders.emptyTop(0) }
    private val sessionBlock = WindowBlockPanel(3)
    private val weeklyBlock = WindowBlockPanel(5)

    init {
        isOpaque = false
        add(separator)
        add(errorLabel)
        add(titleLabel)
        add(sessionBlock)
        add(weeklyBlock)
        hideAll()
    }

    override fun update(quota: ProviderQuota?, error: String?, visible: Boolean) {
        updateContent(quota as? MiniMaxQuota, error, visible)
    }

    private fun updateContent(quota: MiniMaxQuota?, error: String?, visible: Boolean) {
        isVisible = visible
        if (!visible) return

        when {
            error != null -> {
                errorLabel.isVisible = true
                errorLabel.text = "MiniMax error: $error"
                hideContent()
            }
            quota == null -> {
                hideAll()
                titleLabel.isVisible = true
                titleLabel.text = sectionTitle("MiniMax")
                sessionBlock.showLoading("Session")
                weeklyBlock.showLoading("Weekly")
            }
            else -> {
                val limitReached = (quota.sessionUsage?.usagePercent ?: 0.0) >= 100.0 ||
                    (quota.weeklyUsage?.usagePercent ?: 0.0) >= 100.0
                errorLabel.isVisible = limitReached
                if (limitReached) {
                    errorLabel.text = "MiniMax limit reached"
                }

                titleLabel.isVisible = true
                titleLabel.text = sectionTitle("MiniMax", quota.plan.ifBlank { "MiniMax Token Plan (${quota.region})" })
                quota.sessionUsage?.let {
                    sessionBlock.updateMiniMax(it, "Session", QuotaPeriodDurations.ROLLING_5H)
                } ?: sessionBlock.clear()
                quota.weeklyUsage?.let {
                    weeklyBlock.updateMiniMax(it, "Weekly", QuotaPeriodDurations.WEEKLY)
                } ?: weeklyBlock.clear()
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
    }

    private fun WindowBlockPanel.updateMiniMax(
        window: MiniMaxUsageWindow,
        label: String,
        period: java.time.Duration,
    ) {
        val percent = clampPercent(window.usagePercent.roundToInt())
        val resetText = QuotaUiUtil.formatReset(window.resetsAt)
        var info = "$percent% used"
        if (resetText != null) {
            info += " - $resetText"
        } else {
            QuotaUiUtil.formatCompactDuration(window.periodDuration ?: period)?.let { info += " ($it)" }
        }
        update(describeDurationLimitLabel(window.periodDuration ?: period, label), info, percent)
    }
}
