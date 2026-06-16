package de.moritzf.quota.idea.ui.popup

import com.intellij.util.ui.JBUI
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.idea.ui.indicator.clampPercent
import de.moritzf.quota.shared.ProviderQuota
import de.moritzf.quota.supergrok.SuperGrokQuota
import de.moritzf.quota.supergrok.SuperGrokUsageWindow
import kotlin.math.roundToInt

internal class SuperGrokPopupSection : ProviderPopupSection() {
    private val separator = createSeparatedBlock()
    private val errorLabel = createWarningLabel("").apply { border = JBUI.Borders.emptyTop(1) }
    private val titleLabel = createSectionTitleLabel("SuperGrok", QuotaIcons.SUPERGROK).apply { border = JBUI.Borders.emptyTop(0) }
    private val blocks = listOf(WindowBlockPanel(3), WindowBlockPanel(5))

    init {
        isOpaque = false
        add(separator)
        add(errorLabel)
        add(titleLabel)
        blocks.forEach(::add)
        hideAll()
    }

    override fun update(quota: ProviderQuota?, error: String?, visible: Boolean) {
        updateContent(quota as? SuperGrokQuota, error, visible)
    }

    private fun updateContent(quota: SuperGrokQuota?, error: String?, visible: Boolean) {
        isVisible = visible
        if (!visible) return

        when {
            error != null -> {
                errorLabel.isVisible = true
                errorLabel.text = "SuperGrok error: $error"
                hideContent()
            }
            quota == null -> {
                hideAll()
                titleLabel.isVisible = true
                titleLabel.text = "SuperGrok"
                blocks.getOrNull(0)?.showLoading("Monthly credits")
            }
            else -> {
                val usage = quota.creditUsage
                val limitReached = usage != null && (usage.usagePercent >= 100.0 || usage.used >= usage.limit)
                errorLabel.isVisible = limitReached
                if (limitReached) {
                    errorLabel.text = "SuperGrok limit reached"
                }
                titleLabel.isVisible = true
                titleLabel.text = quota.plan.takeIf { it.isNotBlank() } ?: "SuperGrok"
                blocks.forEach { it.clear() }
                if (usage != null) {
                    blocks[0].updateSuperGrok(usage)
                }
                quota.onDemandCap?.takeIf { it > 0 }?.let { cap ->
                    blocks.getOrNull(1)?.update("Pay as you go", "Cap $cap", 0)
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
        blocks.forEach { it.isVisible = false }
    }

    private fun WindowBlockPanel.updateSuperGrok(window: SuperGrokUsageWindow) {
        val percent = clampPercent(window.usagePercent.roundToInt())
        val resetText = QuotaUiUtil.formatReset(window.resetsAt)
        var info = "$percent% used"
        if (resetText != null) info += " - $resetText"
        update("Credits", info, percent)
    }
}
