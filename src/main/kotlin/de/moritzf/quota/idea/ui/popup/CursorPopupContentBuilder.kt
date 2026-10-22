package de.moritzf.quota.idea.ui.popup

import com.intellij.util.ui.JBUI
import de.moritzf.quota.cursor.CursorOnDemandUsage
import de.moritzf.quota.cursor.CursorPlanUsage
import de.moritzf.quota.cursor.CursorQuota
import de.moritzf.quota.cursor.CursorRequestUsage
import de.moritzf.quota.cursor.CursorSpendLimit
import de.moritzf.quota.idea.common.QuotaBundle.message
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.idea.ui.indicator.clampPercent
import de.moritzf.quota.shared.ProviderQuota
import kotlin.math.roundToInt

private const val CURSOR_LABEL = "Cursor"

internal class CursorPopupSection : ProviderPopupSection() {
    private val separator = createSeparatedBlock()
    private val warningLabel = createWarningLabel("").apply { border = JBUI.Borders.emptyTop(1) }
    private val titleLabel =
        createSectionTitleLabel(CURSOR_LABEL, QuotaIcons.CURSOR).apply {
            border = JBUI.Borders.emptyTop(0)
        }
    private val includedSpendBlock = WindowBlockPanel(3)
    private val includedUsageBlock = WindowBlockPanel(5)
    private val autoBlock = WindowBlockPanel(5)
    private val apiBlock = WindowBlockPanel(5)
    private val onDemandBlock = WindowBlockPanel(5)
    private val teamOnDemandBlock = WindowBlockPanel(5)
    private val spendBlock = WindowBlockPanel(5)
    private val grokBlock = WindowBlockPanel(5)

    init {
        isOpaque = false
        add(separator)
        add(warningLabel)
        add(titleLabel)
        add(includedSpendBlock)
        add(includedUsageBlock)
        add(autoBlock)
        add(apiBlock)
        add(onDemandBlock)
        add(teamOnDemandBlock)
        add(spendBlock)
        add(grokBlock)
        hideAll()
    }

    override fun update(quota: ProviderQuota?, error: String?, visible: Boolean) {
        updateContent(quota as? CursorQuota, error, visible)
    }

    private fun updateContent(quota: CursorQuota?, error: String?, visible: Boolean) {
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
                titleLabel.text = sectionTitle(CURSOR_LABEL)
                autoBlock.showLoading("Cursor Models")
                apiBlock.showLoading("Other Models")
            }
            else -> {
                val planUsage = quota.planUsage
                val spendLimit = quota.spendLimit
                val requestUsage = quota.requestUsage

                val limitWarning = getLimitWarning(quota)
                warningLabel.isVisible = limitWarning != null
                if (limitWarning != null) {
                    warningLabel.text = limitWarning
                }

                val planTitle =
                    quota.planName.takeIf { it.isNotBlank() }
                        ?: quota.membershipType.takeIf { it.isNotBlank() }
                titleLabel.isVisible = true
                titleLabel.text = sectionTitle(CURSOR_LABEL, planTitle)

                if (planUsage != null && planUsage.modelPools().isNotEmpty()) {
                    // Legacy dollar caps and request counters are not these pools' allowances.
                    includedSpendBlock.clear()
                    includedUsageBlock.clear()
                    planUsage.autoPercentUsed?.let {
                        autoBlock.updatePercentUsage(
                            it,
                            planUsage.includedResetsAt(),
                            "Cursor Models",
                        )
                    } ?: autoBlock.clear()
                    planUsage.apiPercentUsed?.let {
                        apiBlock.updatePercentUsage(
                            it,
                            planUsage.includedResetsAt(),
                            "Other Models",
                        )
                    } ?: apiBlock.clear()
                } else {
                    planUsage?.let { includedSpendBlock.updateIncludedSpend(it) }
                        ?: includedSpendBlock.clear()
                    if (requestUsage != null) {
                        includedUsageBlock.updateRequestUsage(
                            requestUsage,
                            planUsage?.billingCycleEnd,
                        )
                    } else {
                        planUsage?.usagePercent()?.let {
                            includedUsageBlock.updatePercentUsage(
                                it,
                                planUsage.includedResetsAt(),
                                "Included",
                            )
                        } ?: includedUsageBlock.clear()
                    }
                    autoBlock.clear()
                    apiBlock.clear()
                }

                quota.onDemandUsage?.let {
                    onDemandBlock.updateOnDemandUsage(it, planUsage?.billingCycleEnd, "On-demand")
                } ?: onDemandBlock.clear()
                quota.teamOnDemandUsage?.let {
                    teamOnDemandBlock.updateOnDemandUsage(
                        it,
                        planUsage?.billingCycleEnd,
                        "Team on-demand",
                    )
                } ?: teamOnDemandBlock.clear()
                spendLimit?.let { spendBlock.updateSpendLimit(it) } ?: spendBlock.clear()
                quota.grokUsage?.let { grok ->
                    val percent = clampPercent(grok.usagePercent.roundToInt())
                    val detail =
                        if (grok.trialEndsAt != null) {
                            QuotaUiUtil.formatExpiry(grok.trialEndsAt)?.let {
                                message("quota.cursor.grok.trial", it)
                            }
                        } else {
                            QuotaUiUtil.formatReset(grok.resetsAt)
                        }
                    grokBlock.update(
                        message("quota.cursor.grok.title"),
                        listOfNotNull("$percent% used", detail).joinToString(" - "),
                        percent,
                    )
                } ?: grokBlock.clear()
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
        onDemandBlock.isVisible = false
        teamOnDemandBlock.isVisible = false
        spendBlock.isVisible = false
        grokBlock.isVisible = false
    }

    private fun getLimitWarning(quota: CursorQuota): String? {
        val pools = quota.planUsage?.modelPools().orEmpty()
        val exhaustedPools = pools.filterValues { it >= 100.0 }.keys
        if (exhaustedPools.isNotEmpty()) {
            return "${exhaustedPools.joinToString(" / ")} limit reached"
        }
        if (pools.isEmpty()) {
            if (isLimitNotice(quota.displayMessage)) return quota.displayMessage
            val includedPercent =
                quota.requestUsage?.usagePercent() ?: quota.planUsage?.usagePercent()
            if ((includedPercent ?: 0.0) >= 100.0) {
                return "Cursor limit reached"
            }
        }
        if ((quota.onDemandUsage?.usagePercent() ?: 0.0) >= 100.0) {
            return "Cursor on-demand limit reached"
        }
        if ((quota.teamOnDemandUsage?.usagePercent() ?: 0.0) >= 100.0) {
            return "Cursor team on-demand limit reached"
        }
        if ((quota.spendLimit?.usagePercent() ?: 0.0) >= 100.0) {
            return "Cursor team spend limit reached"
        }
        return null
    }

    private fun isLimitNotice(message: String): Boolean {
        return message.contains("limit", ignoreCase = true) &&
            (message.contains("hit", ignoreCase = true) ||
                message.contains("reached", ignoreCase = true))
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

    private fun WindowBlockPanel.updateRequestUsage(
        usage: CursorRequestUsage,
        resetsAt: kotlin.time.Instant?,
    ) {
        val percent = usage.usagePercent()?.roundToInt()?.let(::clampPercent) ?: 0
        var info = "${usage.used} / ${usage.limit} requests used"
        QuotaUiUtil.formatReset(resetsAt)?.let { info += " - $it" }
        update("Requests", info, percent)
    }

    private fun WindowBlockPanel.updatePercentUsage(
        percentUsed: Double,
        resetsAt: kotlin.time.Instant?,
        label: String,
    ) {
        val percent = clampPercent(percentUsed.roundToInt())
        var info = "$percent% used"
        QuotaUiUtil.formatReset(resetsAt)?.let { info += " - $it" }
        update("$label usage", info, percent)
    }

    private fun WindowBlockPanel.updateOnDemandUsage(
        usage: CursorOnDemandUsage,
        resetsAt: kotlin.time.Instant?,
        label: String,
    ) {
        val percent = usage.usagePercent()?.roundToInt()?.let(::clampPercent) ?: 0
        var info =
            if (usage.limitUsd != null && usage.limitUsd > 0.0) {
                "$${formatUsd(usage.usedUsd)} / $${formatUsd(usage.limitUsd)} used"
            } else {
                "$${formatUsd(usage.usedUsd)} used"
            }
        val remaining = usage.remainingUsd
        if (remaining != null && remaining > 0.0) {
            info += " ($${formatUsd(remaining)} remaining)"
        }
        QuotaUiUtil.formatReset(resetsAt)?.let { info += " - $it" }
        update(label, info, percent)
    }

    private fun WindowBlockPanel.updateSpendLimit(spendLimit: CursorSpendLimit) {
        val percent = spendLimit.usagePercent()?.roundToInt()?.let(::clampPercent) ?: 0
        var info =
            "$${formatUsd(spendLimit.pooledUsedUsd)} / $${formatUsd(spendLimit.pooledLimitUsd)} used"
        if (spendLimit.pooledRemainingUsd > 0.0) {
            info += " ($${formatUsd(spendLimit.pooledRemainingUsd)} remaining)"
        }
        val label =
            if (spendLimit.limitType.isBlank()) "Team spend"
            else "Team spend (${spendLimit.limitType})"
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
