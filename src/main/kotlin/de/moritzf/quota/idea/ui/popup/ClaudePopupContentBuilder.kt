package de.moritzf.quota.idea.ui.popup

import com.intellij.util.ui.JBUI
import de.moritzf.quota.claude.ClaudeQuota
import de.moritzf.quota.claude.ClaudeUsageWindow
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.idea.ui.indicator.clampPercent
import de.moritzf.quota.shared.ProviderQuota
import kotlin.math.roundToInt

internal class ClaudePopupSection : ProviderPopupSection() {
    private val separator = createSeparatedBlock()
    private val errorLabel = createWarningLabel("").apply { border = JBUI.Borders.emptyTop(1) }
    private val titleLabel = createSectionTitleLabel("Claude", QuotaIcons.CLAUDE).apply { border = JBUI.Borders.emptyTop(0) }
    private val fiveHourBlock = WindowBlockPanel(3)
    private val weeklyBlock = WindowBlockPanel(5)
    private val modelBlock = WindowBlockPanel(5)
    private val routinesBlock = WindowBlockPanel(5)
    private val extraBlock = WindowBlockPanel(5)

    init {
        isOpaque = false
        add(separator)
        add(errorLabel)
        add(titleLabel)
        add(fiveHourBlock)
        add(weeklyBlock)
        add(modelBlock)
        add(routinesBlock)
        add(extraBlock)
        hideAll()
    }

    override fun update(quota: ProviderQuota?, error: String?, visible: Boolean) {
        updateContent(quota as? ClaudeQuota, error, visible)
    }

    private fun updateContent(quota: ClaudeQuota?, error: String?, visible: Boolean) {
        isVisible = visible
        if (!visible) return

        when {
            error != null -> {
                errorLabel.isVisible = true
                errorLabel.text = "Claude error: $error"
                hideContent()
            }
            quota == null -> {
                hideAll()
                titleLabel.isVisible = true
                titleLabel.text = "Claude"
                fiveHourBlock.showLoading("5-hour")
                weeklyBlock.showLoading("Weekly")
            }
            else -> {
                val primary = quota.primaryWindow()
                val limitReached = listOfNotNull(
                    quota.fiveHourUsage,
                    quota.sevenDayUsage,
                    quota.sevenDaySonnetUsage,
                    quota.sevenDayOpusUsage,
                    quota.routinesUsage,
                ).any { it.usagePercent >= 100.0 }
                errorLabel.isVisible = limitReached
                if (limitReached) {
                    errorLabel.text = "Claude limit reached"
                }
                titleLabel.isVisible = true
                titleLabel.text = quota.plan.takeIf { it.isNotBlank() } ?: "Claude"

                quota.fiveHourUsage?.let { fiveHourBlock.updateClaude(it, "5-hour") } ?: fiveHourBlock.clear()
                quota.sevenDayUsage?.let { weeklyBlock.updateClaude(it, "Weekly") } ?: weeklyBlock.clear()
                val modelWindow = quota.sevenDaySonnetUsage ?: quota.sevenDayOpusUsage
                modelWindow?.let { modelBlock.updateClaude(it, it.label.ifBlank { "Model weekly" }) }
                    ?: modelBlock.clear()
                quota.routinesUsage?.let { routinesBlock.updateClaude(it, "Daily Routines") } ?: routinesBlock.clear()
                val extra = quota.extraUsage
                if (extra?.isEnabled == true) {
                    val percent = extra.usagePercent?.roundToInt()?.let(::clampPercent) ?: 0
                    val used = extra.usedMajor
                    val limit = extra.monthlyLimitMajor
                    val currency = extra.currency?.takeIf { it.isNotBlank() } ?: "USD"
                    val money = if (used != null && limit != null) {
                        String.format("%.2f / %.2f %s", used, limit, currency)
                    } else {
                        null
                    }
                    var info = "$percent% used"
                    if (money != null) info += " • $money"
                    extraBlock.update("Extra usage", info, percent)
                } else {
                    extraBlock.clear()
                }
                if (primary == null && extra?.isEnabled != true) {
                    fiveHourBlock.showLoading("Usage")
                }
            }
        }
    }

    private fun hideAll() {
        errorLabel.isVisible = false
        hideContent()
    }

    private fun hideContent() {
        titleLabel.isVisible = false
        fiveHourBlock.isVisible = false
        weeklyBlock.isVisible = false
        modelBlock.isVisible = false
        routinesBlock.isVisible = false
        extraBlock.isVisible = false
    }

    private fun WindowBlockPanel.updateClaude(window: ClaudeUsageWindow, label: String) {
        val percent = clampPercent(window.usagePercent.roundToInt())
        val resetText = QuotaUiUtil.formatReset(window.resetsAt)
        var info = "$percent% used"
        if (resetText != null) info += " - $resetText"
        update(label, info, percent)
    }
}
