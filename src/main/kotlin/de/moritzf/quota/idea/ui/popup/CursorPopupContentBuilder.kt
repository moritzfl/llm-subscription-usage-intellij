package de.moritzf.quota.idea.ui.popup

import de.moritzf.quota.cursor.CursorPlanUsage
import de.moritzf.quota.cursor.CursorQuota
import de.moritzf.quota.cursor.CursorSpendLimit
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.idea.ui.indicator.clampPercent
import kotlin.math.roundToInt
import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.util.ui.JBUI
import javax.swing.JPanel

private const val CURSOR_LABEL = "Cursor"

internal class CursorPopupSection : JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 0, true, false)) {
    private val separator = createSeparatedBlock()
    private val warningLabel = createWarningLabel("").apply { border = JBUI.Borders.emptyTop(1) }
    private val titleLabel = createSectionTitleLabel(CURSOR_LABEL, QuotaIcons.CURSOR).apply { border = JBUI.Borders.emptyTop(0) }
    private val includedSpendBlock = WindowBlockPanel(3)
    private val includedUsageBlock = WindowBlockPanel(5)
    private val autoBlock = WindowBlockPanel(5)
    private val apiBlock = WindowBlockPanel(5)
    private val spendBlock = WindowBlockPanel(5)

    init {
        isOpaque = false
        add(separator)
        add(warningLabel)
        add(titleLabel)
        add(includedSpendBlock)
        add(includedUsageBlock)
        add(autoBlock)
        add(apiBlock)
        add(spendBlock)
        hideAll()
    }

    fun update(quota: CursorQuota?, error: String?, visible: Boolean) {
        isVisible = visible
        if (!visible) return

        when {
            error != null -> {
                warningLabel.isVisible = true
                warningLabel.text = "Cursor error: $error"
                hideContent()
            }
            quota == null -> {
                hideAll()
                titleLabel.isVisible = true
                titleLabel.text = CURSOR_LABEL
                includedSpendBlock.showLoading("Included spend")
                includedUsageBlock.showLoading("Included")
                autoBlock.showLoading("Auto")
                apiBlock.showLoading("API")
            }
            else -> {
                val planUsage = quota.planUsage
                val spendLimit = quota.spendLimit

                val limitWarning = getLimitWarning(quota)
                warningLabel.isVisible = limitWarning != null
                if (limitWarning != null) {
                    warningLabel.text = limitWarning
                }

                val planTitle = quota.planName.takeIf { it.isNotBlank() }
                    ?: quota.membershipType.takeIf { it.isNotBlank() }
                titleLabel.isVisible = true
                titleLabel.text = if (planTitle != null) "$CURSOR_LABEL ($planTitle)" else CURSOR_LABEL

                planUsage?.let { includedSpendBlock.updateIncludedSpend(it) } ?: includedSpendBlock.clear()
                includedUsageBlock.updateIncludedUsage(quota, planUsage)

                if (planUsage != null && shouldShowAutoUsage(planUsage, quota)) {
                    autoBlock.updatePercentUsage(planUsage.autoPercentUsed, planUsage.billingCycleEnd, "Auto")
                } else {
                    autoBlock.clear()
                }

                planUsage?.let { apiBlock.updateApiUsage(it) } ?: apiBlock.clear()
                spendLimit?.let { spendBlock.updateSpendLimit(it) } ?: spendBlock.clear()
            }
        }
    }

    private fun hideAll() {
        warningLabel.isVisible = false
        hideContent()
    }

    private fun hideContent() {
        titleLabel.isVisible = false
        includedSpendBlock.isVisible = false
        includedUsageBlock.isVisible = false
        autoBlock.isVisible = false
        apiBlock.isVisible = false
        spendBlock.isVisible = false
    }

    private fun getLimitWarning(quota: CursorQuota): String? {
        if (!isLimitReached(quota)) {
            return null
        }
        return quota.displayMessage.takeIf { it.isNotBlank() && isLimitNotice(it) }
            ?: "Cursor limit reached"
    }

    private fun isLimitReached(quota: CursorQuota): Boolean {
        val planUsage = quota.planUsage
        if (planUsage != null) {
            if (planUsage.limitUsd > 0.0 && planUsage.totalSpendUsd >= planUsage.limitUsd) {
                return true
            }
            if (planUsage.totalPercentUsed >= 100.0) {
                return true
            }
        }
        if (quota.displayMessage.isNotBlank() && isLimitNotice(quota.displayMessage)) {
            return true
        }
        val spendLimit = quota.spendLimit ?: return false
        return (spendLimit.usagePercent() ?: 0.0) >= 100.0
    }

    private fun isLimitNotice(message: String): Boolean {
        return message.contains("limit", ignoreCase = true) &&
            (message.contains("hit", ignoreCase = true) || message.contains("reached", ignoreCase = true))
    }

    private fun shouldShowAutoUsage(planUsage: CursorPlanUsage, quota: CursorQuota): Boolean {
        val totalPercent = resolveIncludedUsagePercent(quota, planUsage)
        return clampPercent(planUsage.autoPercentUsed.roundToInt()) != totalPercent
    }

    private fun resolveIncludedUsagePercent(quota: CursorQuota, planUsage: CursorPlanUsage): Int {
        val message = quota.autoModelDisplayMessage.takeIf { it.isNotBlank() }
        message?.let { parseDisplayMessagePercent(it) }?.let { return it }
        return clampPercent(planUsage.totalPercentUsed.roundToInt())
    }

    private fun WindowBlockPanel.updateIncludedUsage(quota: CursorQuota, planUsage: CursorPlanUsage?) {
        if (planUsage == null) {
            clear()
            return
        }

        val percent = resolveIncludedUsagePercent(quota, planUsage)
        var info = "$percent% used"
        QuotaUiUtil.formatReset(planUsage.billingCycleEnd)?.let { info += " - $it" }
        update("Included usage", info, percent)
    }

    private fun WindowBlockPanel.updateIncludedSpend(usage: CursorPlanUsage) {
        if (usage.limitUsd <= 0.0) {
            clear()
            return
        }
        val percent = clampPercent((usage.totalSpendUsd / usage.limitUsd * 100.0).roundToInt())
        var info = "$${formatUsd(usage.totalSpendUsd)} / $${formatUsd(usage.limitUsd)} used"
        QuotaUiUtil.formatReset(usage.billingCycleEnd)?.let { info += " - $it" }
        update("Included spend", info, percent)
    }

    private fun WindowBlockPanel.updateApiUsage(planUsage: CursorPlanUsage) {
        val percent = clampPercent(planUsage.apiPercentUsed.roundToInt())
        var info = "$percent% used"
        QuotaUiUtil.formatReset(planUsage.billingCycleEnd)?.let { info += " - $it" }
        update("API usage", info, percent)
    }

    private fun WindowBlockPanel.updatePercentUsage(percentUsed: Double, resetsAt: kotlinx.datetime.Instant?, label: String) {
        val percent = clampPercent(percentUsed.roundToInt())
        var info = "$percent% used"
        QuotaUiUtil.formatReset(resetsAt)?.let { info += " - $it" }
        update("$label usage", info, percent)
    }

    private fun WindowBlockPanel.updateSpendLimit(spendLimit: CursorSpendLimit) {
        val percent = spendLimit.usagePercent()?.roundToInt()?.let(::clampPercent) ?: 0
        var info = "$${formatUsd(spendLimit.pooledUsedUsd)} / $${formatUsd(spendLimit.pooledLimitUsd)} used"
        if (spendLimit.pooledRemainingUsd > 0.0) {
            info += " ($${formatUsd(spendLimit.pooledRemainingUsd)} remaining)"
        }
        val label = if (spendLimit.limitType.isBlank()) "Team spend" else "Team spend (${spendLimit.limitType})"
        update(label, info, percent)
    }

    private fun formatUsd(value: Double): String {
        return if (value >= 100.0) {
            value.roundToInt().toString()
        } else {
            String.format("%.2f", value)
        }
    }
}
