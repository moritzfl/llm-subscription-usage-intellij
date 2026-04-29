package de.moritzf.quota.idea.ui.popup

import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.idea.ui.indicator.clampPercent
import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.openai.UsageWindow
import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.util.ui.JBUI
import javax.swing.JPanel
import kotlin.math.roundToInt

internal class OpenAiPopupSection : JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 0, true, false)) {
    private val separator = createSeparatedBlock()
    private val warningLabel = createWarningLabel("").apply { border = JBUI.Borders.emptyTop(1) }
    private val titleLabel = createSectionTitleLabel("Codex", QuotaIcons.OPENAI).apply { border = JBUI.Borders.emptyTop(0) }
    private val primaryBlock = WindowBlockPanel(3)
    private val secondaryBlock = WindowBlockPanel(5)
    private val reviewSeparator = createSeparatedBlock()
    private val reviewTitle = createSectionTitleLabel("Code Review", QuotaIcons.OPENAI).apply { border = JBUI.Borders.emptyTop(0) }
    private val reviewPrimaryBlock = WindowBlockPanel(3)
    private val reviewSecondaryBlock = WindowBlockPanel(5)

    init {
        isOpaque = false
        add(separator)
        add(warningLabel)
        add(titleLabel)
        add(primaryBlock)
        add(secondaryBlock)
        add(reviewSeparator)
        add(reviewTitle)
        add(reviewPrimaryBlock)
        add(reviewSecondaryBlock)
        hideAll()
    }

    fun update(quota: OpenAiCodexQuota?, error: String?, visible: Boolean, hasReviewData: Boolean) {
        isVisible = visible
        if (!visible) return

        when {
            error != null -> {
                warningLabel.isVisible = true
                warningLabel.text = "Codex error: $error"
                hideContent()
            }
            quota == null -> {
                hideAll()
                titleLabel.isVisible = true
                titleLabel.text = "Codex"
                primaryBlock.showLoading("5h")
                secondaryBlock.showLoading("Weekly")
            }
            else -> {
                val limitWarning = getLimitWarning(quota)
                warningLabel.isVisible = limitWarning != null
                if (limitWarning != null) warningLabel.text = limitWarning

                val hasMainData = quota.primary != null || quota.secondary != null
                titleLabel.isVisible = hasMainData
                if (hasMainData) {
                    val planLabel = quota.planType?.toDisplayLabel()
                    titleLabel.text = if (!planLabel.isNullOrBlank()) "Codex ($planLabel)" else "Codex"
                }

                quota.primary?.let { primaryBlock.updateWindow(it, "Primary") } ?: primaryBlock.clear()
                quota.secondary?.let { secondaryBlock.updateWindow(it, "Secondary") } ?: secondaryBlock.clear()

                reviewSeparator.isVisible = hasReviewData
                reviewTitle.isVisible = hasReviewData
                quota.reviewPrimary?.let { reviewPrimaryBlock.updateWindow(it, "Primary") } ?: reviewPrimaryBlock.clear()
                quota.reviewSecondary?.let { reviewSecondaryBlock.updateWindow(it, "Secondary") } ?: reviewSecondaryBlock.clear()
            }
        }
    }

    private fun hideAll() {
        warningLabel.isVisible = false
        hideContent()
    }

    private fun hideContent() {
        titleLabel.isVisible = false
        primaryBlock.isVisible = false
        secondaryBlock.isVisible = false
        reviewSeparator.isVisible = false
        reviewTitle.isVisible = false
        reviewPrimaryBlock.isVisible = false
        reviewSecondaryBlock.isVisible = false
    }

    private fun WindowBlockPanel.updateWindow(window: UsageWindow, fallbackLabel: String) {
        val percent = clampPercent(window.usedPercent.roundToInt())
        val title = describeWindowLabel(window, fallbackLabel)
        val resetText = QuotaUiUtil.formatReset(window.resetsAt)
        var info = "$percent% used"
        if (resetText != null) info += " - $resetText"
        update(title, info, percent)
    }
}
