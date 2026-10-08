package de.moritzf.quota.idea.ui.popup

import com.intellij.util.ui.JBUI
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.idea.ui.indicator.clampPercent
import de.moritzf.quota.mistral.MistralQuota
import de.moritzf.quota.mistral.MistralUsageWindow
import de.moritzf.quota.shared.ProviderQuota
import kotlin.math.roundToInt

private const val MISTRAL_LABEL = "Mistral"

internal class MistralPopupSection : ProviderPopupSection() {
    private val separator = createSeparatedBlock()
    private val errorLabel = createWarningLabel("").apply { border = JBUI.Borders.emptyTop(1) }
    private val titleLabel =
        createSectionTitleLabel(MISTRAL_LABEL, QuotaIcons.MISTRAL).apply {
            border = JBUI.Borders.emptyTop(0)
        }
    private val monthlyBlock = WindowBlockPanel(3)
    private val includedApiBlock = WindowBlockPanel(3)
    private val apiUsageBlock = WindowBlockPanel(3)

    init {
        isOpaque = false
        add(separator)
        add(errorLabel)
        add(titleLabel)
        add(includedApiBlock)
        add(monthlyBlock)
        add(apiUsageBlock)
        hideAll()
    }

    override fun update(quota: ProviderQuota?, error: String?, visible: Boolean) {
        updateContent(quota as? MistralQuota, error, visible)
    }

    private fun updateContent(quota: MistralQuota?, error: String?, visible: Boolean) {
        isVisible = visible
        if (!visible) return

        when {
            error != null -> {
                errorLabel.isVisible = true
                errorLabel.text = "Mistral error: $error"
                hideContent()
            }
            quota == null -> {
                hideAll()
                titleLabel.isVisible = true
                titleLabel.text = sectionTitle(MISTRAL_LABEL)
                includedApiBlock.showLoading("Monthly API")
                monthlyBlock.showLoading("Monthly Mistral Vibe")
                apiUsageBlock.showLoading("API usage")
            }
            else -> {
                val limitReached =
                    (quota.monthlyUsage?.usagePercent ?: 0.0) >= 100.0 ||
                        (quota.includedApiUsage?.usagePercent ?: 0.0) >= 100.0
                errorLabel.isVisible = limitReached
                if (limitReached) {
                    errorLabel.text = "Mistral limit reached"
                }
                titleLabel.isVisible = true
                titleLabel.text = sectionTitle(MISTRAL_LABEL)
                quota.includedApiUsage?.let {
                    includedApiBlock.updateMistral(it, "Monthly API limit")
                } ?: includedApiBlock.clear()
                quota.monthlyUsage?.let {
                    monthlyBlock.updateMistral(it, "Monthly Mistral Vibe limit")
                } ?: monthlyBlock.clear()
                quota.apiUsage?.let {
                    apiUsageBlock.showUnavailable("API usage (month)", apiUsageInfo(it))
                } ?: apiUsageBlock.clear()
            }
        }
    }

    private fun hideAll() {
        errorLabel.isVisible = false
        hideContent()
    }

    private fun hideContent() {
        titleLabel.isVisible = false
        monthlyBlock.isVisible = false
        includedApiBlock.isVisible = false
        apiUsageBlock.isVisible = false
    }

    private fun WindowBlockPanel.updateMistral(window: MistralUsageWindow, label: String) {
        val percent = clampPercent(window.usagePercent.roundToInt())
        val resetText = QuotaUiUtil.formatReset(window.resetsAt)
        var info = "$percent% used"
        if (window.usedAmount != null && window.limitAmount != null && window.currency != null) {
            val money =
                java.text.NumberFormat.getCurrencyInstance(java.util.Locale.getDefault()).apply {
                    currency = java.util.Currency.getInstance(window.currency)
                }
            info += " • ${money.format(window.usedAmount)} / ${money.format(window.limitAmount)}"
        }
        if (resetText != null) info += " - $resetText"
        update(label, info, percent)
    }

    /** Activity totals complement the separate included-allowance bars. */
    private fun apiUsageInfo(usage: de.moritzf.quota.mistral.MistralApiUsage): String {
        val parts = mutableListOf<String>()
        usage.spendEur?.let { parts += String.format(java.util.Locale.ROOT, "€%.2f", it) }
        if (usage.tokens > 0) parts += "${QuotaUiUtil.formatCompactCount(usage.tokens)} tokens"
        if (usage.ocrPages > 0)
            parts += "${QuotaUiUtil.formatCompactCount(usage.ocrPages)} OCR pages"
        if (usage.audioSeconds > 0)
            parts += "${QuotaUiUtil.formatCompactCount(usage.audioSeconds)} audio sec"
        if (parts.isEmpty())
            parts += if (usage.hasAnyUsage()) "API usage recorded" else "No API usage this month"
        return parts.joinToString(" • ")
    }
}
