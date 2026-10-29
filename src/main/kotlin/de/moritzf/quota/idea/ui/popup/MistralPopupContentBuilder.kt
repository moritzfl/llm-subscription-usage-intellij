package de.moritzf.quota.idea.ui.popup

import com.intellij.util.ui.JBUI
import de.moritzf.quota.idea.common.QuotaBundle.message
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.idea.ui.indicator.clampPercent
import de.moritzf.quota.mistral.MistralApiUsage
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
    private val balanceLabel = createMutedLabel("").apply { border = JBUI.Borders.emptyTop(2) }

    init {
        isOpaque = false
        add(separator)
        add(errorLabel)
        add(titleLabel)
        add(includedApiBlock)
        add(monthlyBlock)
        add(apiUsageBlock)
        add(balanceLabel)
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
                val credits = quota.credits
                val apiLimitReached = (quota.includedApiUsage?.usagePercent ?: 0.0) >= 100.0
                val vibeLimitReached = (quota.monthlyUsage?.usagePercent ?: 0.0) >= 100.0
                // Credits only extend the included allowance while pay-as-you-go is enabled.
                val apiCoveredByCredits =
                    apiLimitReached &&
                        quota.includedApiUsage?.payAsYouGoEnabled == true &&
                        (credits?.availableAmount ?: 0.0) > 0.0
                errorLabel.isVisible = (apiLimitReached && !apiCoveredByCredits) || vibeLimitReached
                errorLabel.text =
                    when {
                        apiLimitReached && !apiCoveredByCredits && vibeLimitReached ->
                            "Monthly API and Vibe limits reached"
                        apiLimitReached && !apiCoveredByCredits -> "Monthly API limit reached"
                        vibeLimitReached -> "Monthly Vibe limit reached"
                        else -> ""
                    }
                titleLabel.isVisible = true
                titleLabel.text = sectionTitle(MISTRAL_LABEL)
                val apiUsageTooltip = apiUsageTooltip(quota.apiUsage)
                quota.includedApiUsage?.let {
                    includedApiBlock.updateMistral(it, "Monthly API limit", apiUsageTooltip)
                } ?: includedApiBlock.clear()
                quota.monthlyUsage?.let {
                    monthlyBlock.updateMistral(it, "Monthly Mistral Vibe limit")
                } ?: monthlyBlock.clear()
                if (quota.includedApiUsage == null) {
                    quota.apiUsage?.let {
                        apiUsageBlock.showUnavailable(
                            "API usage (month)",
                            apiUsageSummary(it),
                            apiUsageTooltip,
                        )
                    } ?: apiUsageBlock.clear()
                } else {
                    apiUsageBlock.clear()
                }
                balanceLabel.isVisible = credits != null
                balanceLabel.text =
                    credits
                        ?.let {
                            message(
                                "quota.credit.balance",
                                QuotaUiUtil.formatMoney(it.availableAmount, it.currency),
                            )
                        }
                        .orEmpty()
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
        balanceLabel.isVisible = false
    }

    private fun WindowBlockPanel.updateMistral(
        window: MistralUsageWindow,
        label: String,
        detailTooltip: String? = null,
    ) {
        val percent = window.usagePercent.roundToInt()
        val resetText = QuotaUiUtil.formatReset(window.resetsAt)
        var info = "$percent% used"
        if (window.usedAmount != null && window.limitAmount != null && window.currency != null) {
            info +=
                " • ${QuotaUiUtil.formatMoney(window.usedAmount, window.currency)} / " +
                    QuotaUiUtil.formatMoney(window.limitAmount, window.currency)
        }
        if (resetText != null) info += " - $resetText"
        update(label, info, clampPercent(percent), detailTooltip)
    }

    /** A single fallback figure when the allowance itself is unavailable. */
    private fun apiUsageSummary(usage: MistralApiUsage): String {
        usage.spendEur?.let {
            return QuotaUiUtil.formatMoney(it, "EUR")
        }
        if (usage.tokens > 0) return "${QuotaUiUtil.formatCompactCount(usage.tokens)} tokens"
        return if (usage.hasAnyUsage()) "Usage recorded" else "No API usage this month"
    }

    /** Activity totals complement the separate included-allowance bars, on hover only. */
    private fun apiUsageTooltip(usage: MistralApiUsage?): String? {
        if (usage == null || !usage.hasAnyUsage()) return null
        val parts = mutableListOf<String>()
        usage.spendEur?.let { parts += "Spend ${QuotaUiUtil.formatMoney(it, "EUR")}" }
        if (usage.tokens > 0) {
            parts += "${QuotaUiUtil.formatCompactCount(usage.tokens)} tokens"
        }
        if (usage.ocrPages > 0) {
            parts += "${QuotaUiUtil.formatCompactCount(usage.ocrPages)} OCR pages"
        }
        if (usage.connectorCalls > 0) {
            parts += "${QuotaUiUtil.formatCompactCount(usage.connectorCalls)} connector calls"
        }
        if (usage.audioSeconds > 0) {
            parts += "${QuotaUiUtil.formatCompactCount(usage.audioSeconds)} audio sec"
        }
        if (usage.ttsCharacters > 0) {
            parts += "${QuotaUiUtil.formatCompactCount(usage.ttsCharacters)} TTS characters"
        }
        return parts.joinToString(" • ").takeIf { it.isNotEmpty() }
    }
}
