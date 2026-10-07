package de.moritzf.quota.idea.ui.popup

import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.idea.ui.indicator.QuotaPeriodDurations
import de.moritzf.quota.idea.ui.indicator.clampPercent
import de.moritzf.quota.ollama.OllamaQuota
import kotlin.math.roundToInt
import de.moritzf.quota.ollama.OllamaUsageWindow
import com.intellij.util.ui.JBUI
import de.moritzf.quota.shared.ProviderQuota

private const val OLLAMA_LABEL = "Ollama Cloud"

internal class OllamaPopupSection : ProviderPopupSection() {
    private val separator = createSeparatedBlock()
    private val errorLabel = createWarningLabel("").apply { border = JBUI.Borders.emptyTop(1) }
    private val titleLabel = createSectionTitleLabel(OLLAMA_LABEL, QuotaIcons.OLLAMA).apply { border = JBUI.Borders.emptyTop(0) }
    private val sessionBlock = WindowBlockPanel(3)
    private val weeklyBlock = WindowBlockPanel(5)
    private val monthlyBlock = WindowBlockPanel(5)

    init {
        isOpaque = false
        add(separator)
        add(errorLabel)
        add(titleLabel)
        add(sessionBlock)
        add(weeklyBlock)
        add(monthlyBlock)
        hideAll()
    }

    override fun update(quota: ProviderQuota?, error: String?, visible: Boolean) {
        updateContent(quota as? OllamaQuota, error, visible)
    }

    private fun updateContent(quota: OllamaQuota?, error: String?, visible: Boolean) {
        isVisible = visible
        if (!visible) return

        when {
            error != null -> {
                errorLabel.isVisible = true
                errorLabel.text = "Ollama error: $error"
                hideContent()
            }
            quota == null -> {
                hideAll()
                titleLabel.isVisible = true
                titleLabel.text = sectionTitle(OLLAMA_LABEL)
                sessionBlock.showLoading("Session")
                weeklyBlock.showLoading("Weekly")
                monthlyBlock.showLoading("Monthly")
            }
            else -> {
                val limitReached = (quota.sessionUsage?.usagePercent ?: 0.0) >= 100.0 ||
                    (quota.weeklyUsage?.usagePercent ?: 0.0) >= 100.0 ||
                    (quota.monthlyUsage?.usagePercent ?: 0.0) >= 100.0
                errorLabel.isVisible = limitReached
                if (limitReached) {
                    errorLabel.text = "Ollama limit reached"
                }

                titleLabel.isVisible = true
                titleLabel.text = sectionTitle(OLLAMA_LABEL)
                quota.sessionUsage?.let {
                    sessionBlock.updateOllama(it, "Session", QuotaPeriodDurations.ROLLING_5H)
                } ?: sessionBlock.clear()
                quota.weeklyUsage?.let {
                    weeklyBlock.updateOllama(it, "Weekly", QuotaPeriodDurations.WEEKLY)
                } ?: weeklyBlock.clear()
                quota.monthlyUsage?.let {
                    monthlyBlock.updateOllama(it, "Monthly", QuotaPeriodDurations.MONTHLY)
                } ?: monthlyBlock.clear()
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
        monthlyBlock.isVisible = false
    }

    private fun WindowBlockPanel.updateOllama(
        window: OllamaUsageWindow,
        label: String,
        period: java.time.Duration,
    ) {
        val percent = clampPercent(window.usagePercent.roundToInt())
        val resetText = QuotaUiUtil.formatReset(window.resetsAt)
        var info = "$percent% used"
        if (window.usedAmountUsd != null && window.allowanceUsd != null) {
            info += String.format(java.util.Locale.ROOT, " • $%.2f / $%.2f", window.usedAmountUsd, window.allowanceUsd)
        }
        if (resetText != null) {
            info += " - $resetText"
        } else {
            // API omits resets_at; show known window length instead of leaving time blank/"unknown".
            QuotaUiUtil.formatCompactDuration(period)?.let { info += " ($it)" }
        }
        update("$label limit", info, percent)
    }
}
