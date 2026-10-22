package de.moritzf.quota.idea.ui.popup

import com.intellij.util.ui.JBUI
import de.moritzf.quota.idea.common.QuotaBundle.message
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.idea.ui.indicator.clampPercent
import de.moritzf.quota.kimi.KimiExtraUsage
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
    private val weeklyBlock = WindowBlockPanel(5)
    private val monthlyBlock = WindowBlockPanel(5)
    private val overallBlock = WindowBlockPanel(5)
    private val extraBlock = WindowBlockPanel(5)
    private val balanceLabel = createMutedLabel("").apply { border = JBUI.Borders.emptyTop(2) }

    init {
        isOpaque = false
        add(separator)
        add(errorLabel)
        add(titleLabel)
        add(sessionBlock)
        add(weeklyBlock)
        add(monthlyBlock)
        add(overallBlock)
        add(extraBlock)
        add(balanceLabel)
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
                weeklyBlock.isVisible = false
                monthlyBlock.isVisible = false
                overallBlock.isVisible = false
                extraBlock.isVisible = false
                balanceLabel.isVisible = false
            }

            quota == null -> {
                hideAll()
                titleLabel.isVisible = true
                titleLabel.text = sectionTitle("Kimi Code")
                sessionBlock.showLoading("Session")
                overallBlock.showLoading("Overall")
            }

            else -> {
                val limitReached = quota.windows().any { it.usagePercent >= 100.0 }
                errorLabel.isVisible = limitReached
                if (limitReached) {
                    errorLabel.text = "Kimi limit reached"
                }

                titleLabel.isVisible = true
                titleLabel.text = sectionTitle("Kimi Code", quota.plan.takeIf { it.isNotBlank() })
                quota.sessionUsage?.let { sessionBlock.updateKimi(it, "Session") }
                    ?: sessionBlock.clear()
                quota.weeklyUsage?.let { weeklyBlock.updateKimi(it, "Weekly") }
                    ?: weeklyBlock.clear()
                quota.monthlyUsage?.let { monthly ->
                    monthlyBlock.updateKimi(
                        monthly,
                        "Monthly",
                        quota.monthlyCodeUsage?.let {
                            message(
                                "quota.kimi.monthly.code",
                                clampPercent(it.usagePercent.roundToInt()),
                            )
                        },
                    )
                } ?: monthlyBlock.clear()
                quota.totalUsage?.let { overallBlock.updateKimi(it, "Overall") }
                    ?: overallBlock.clear()
                updateExtraUsage(quota.extraUsage)
            }
        }
    }

    private fun hideAll() {
        errorLabel.isVisible = false
        titleLabel.isVisible = false
        sessionBlock.isVisible = false
        weeklyBlock.isVisible = false
        monthlyBlock.isVisible = false
        overallBlock.isVisible = false
        extraBlock.isVisible = false
        balanceLabel.isVisible = false
    }

    private fun updateExtraUsage(extra: KimiExtraUsage?) {
        balanceLabel.isVisible = extra != null
        if (extra == null) {
            extraBlock.clear()
            return
        }
        val title = message("quota.kimi.extra.title")
        val used = money(extra.usedCents, extra.currency)
        val percent = extra.usagePercent
        val limit = extra.limitCents
        if (percent != null && limit != null) {
            extraBlock.update(
                title,
                message("quota.kimi.extra.used", used, money(limit, extra.currency)),
                clampPercent(percent.roundToInt()),
            )
        } else {
            extraBlock.showUnavailable(title, message("quota.kimi.extra.uncapped", used))
        }
        balanceLabel.text =
            message("quota.kimi.extra.balance", money(extra.balanceCents, extra.currency))
    }

    private fun money(cents: Long, currency: String): String {
        val amount = String.format("%.2f", cents / 100.0)
        return when (currency.uppercase()) {
            "CNY" -> "¥$amount"
            "USD" -> "\$$amount"
            else -> "$amount ${currency.uppercase()}"
        }
    }

    private fun WindowBlockPanel.updateKimi(
        window: KimiUsageWindow,
        label: String,
        breakdown: String? = null,
    ) {
        val percent = clampPercent(window.usagePercent.roundToInt())
        val resetText = QuotaUiUtil.formatReset(window.resetsAt)
        var info = "$percent% used"
        if (breakdown != null) info += " ($breakdown)"
        if (resetText != null) info += " - $resetText"
        update(describeDurationLimitLabel(window.periodDuration, label), info, percent)
    }
}
