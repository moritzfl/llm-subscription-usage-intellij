package de.moritzf.quota.idea.ui.popup

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.ActionLink
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.idea.ui.indicator.clampPercent
import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.openai.OpenAiCredits
import de.moritzf.quota.openai.RateLimitResetCredit
import de.moritzf.quota.openai.OpenAiSpendControl
import de.moritzf.quota.openai.UsageWindow
import de.moritzf.quota.openai.formatApproxMessages
import de.moritzf.quota.openai.isAssignedCreditsQuota
import com.intellij.util.ui.JBUI
import de.moritzf.quota.shared.ProviderQuota
import javax.swing.JLabel
import javax.swing.JPanel
import kotlin.math.roundToInt
import java.util.Locale
import java.awt.Cursor
import java.awt.FlowLayout

internal class OpenAiPopupSection : ProviderPopupSection() {
    private val separator = createSeparatedBlock()
    private val warningLabel = createWarningLabel("").apply { border = JBUI.Borders.emptyTop(1) }
    private val titleLabel = createSectionTitleLabel("Codex", QuotaIcons.OPENAI).apply { border = JBUI.Borders.emptyTop(0) }
    private val primaryBlock = WindowBlockPanel(3)
    private val secondaryBlock = WindowBlockPanel(5)
    private val creditsBlock = WindowBlockPanel(5)
    private val resetCreditsPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(3), 0)).apply {
        isOpaque = false
        border = JBUI.Borders.emptyTop(5)
    }
    private val extraLimitBlocks = mutableListOf<WindowBlockPanel>()
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
        add(creditsBlock)
        add(resetCreditsPanel)
        add(reviewSeparator)
        add(reviewTitle)
        add(reviewPrimaryBlock)
        add(reviewSecondaryBlock)
        hideAll()
    }

    override fun update(quota: ProviderQuota?, error: String?, visible: Boolean) {
        val codexQuota = quota as? OpenAiCodexQuota
        val hasReviewData = codexQuota != null && (
            codexQuota.reviewPrimary != null || codexQuota.reviewSecondary != null ||
                codexQuota.reviewAllowed != null || codexQuota.reviewLimitReached != null
            )
        updateContent(codexQuota, error, visible, hasReviewData)
    }

    private fun updateContent(quota: OpenAiCodexQuota?, error: String?, visible: Boolean, hasReviewData: Boolean) {
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
                creditsBlock.showLoading("Assigned credits")
            }
            else -> {
                val limitWarning = getLimitWarning(quota)
                warningLabel.isVisible = limitWarning != null
                if (limitWarning != null) warningLabel.text = limitWarning

                val hasMainData = quota.primary != null || quota.secondary != null || quota.isAssignedCreditsQuota()
                titleLabel.isVisible = hasMainData
                if (hasMainData) {
                    val planLabel = quota.planType?.toDisplayLabel()
                    titleLabel.text = if (!planLabel.isNullOrBlank()) "Codex ($planLabel)" else "Codex"
                }

                quota.primary?.let { primaryBlock.updateWindow(it, "Primary") } ?: primaryBlock.clear()
                quota.secondary?.let { secondaryBlock.updateWindow(it, "Secondary") } ?: secondaryBlock.clear()
                if (quota.isAssignedCreditsQuota() && quota.credits != null) {
                    creditsBlock.updateAssignedCredits(quota.credits!!, quota.spendControl, quota.rateLimitReachedType)
                } else {
                    creditsBlock.clear()
                }
                updateResetCredits(quota.resetCreditsAvailableCount, quota.resetCredits)
                ensureExtraLimitBlockCount(quota.extraRateLimits.size)
                extraLimitBlocks.forEachIndexed { index, block ->
                    quota.extraRateLimits.getOrNull(index)?.let { extra ->
                        block.updateNamedWindow(extra.window, extra.title)
                    } ?: block.clear()
                }

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
        creditsBlock.isVisible = false
        resetCreditsPanel.isVisible = false
        extraLimitBlocks.forEach { it.isVisible = false }
        reviewSeparator.isVisible = false
        reviewTitle.isVisible = false
        reviewPrimaryBlock.isVisible = false
        reviewSecondaryBlock.isVisible = false
    }

    private fun WindowBlockPanel.updateWindow(window: UsageWindow, fallbackLabel: String) {
        val percent = clampPercent(window.usedPercent.roundToInt())
        val title = describeWindowLabel(window, fallbackLabel)
        updateWindowContent(window, title, percent)
    }

    private fun WindowBlockPanel.updateNamedWindow(window: UsageWindow, title: String) {
        val percent = clampPercent(window.usedPercent.roundToInt())
        updateWindowContent(window, title, percent)
    }

    private fun WindowBlockPanel.updateWindowContent(window: UsageWindow, title: String, percent: Int) {
        val resetText = QuotaUiUtil.formatReset(window.resetsAt)
        var info = "$percent% used"
        if (resetText != null) info += " - $resetText"
        update(title, info, percent)
    }

    private fun ensureExtraLimitBlockCount(count: Int) {
        if (count <= extraLimitBlocks.size) return
        repeat(count - extraLimitBlocks.size) {
            val block = WindowBlockPanel(5)
            extraLimitBlocks.add(block)
            val resetIndex = getComponentZOrder(resetCreditsPanel).takeIf { it >= 0 } ?: componentCount
            add(block, resetIndex)
        }
        revalidate()
    }

    private fun WindowBlockPanel.updateAssignedCredits(
        credits: OpenAiCredits,
        spendControl: OpenAiSpendControl?,
        rateLimitReachedType: String?,
    ) {
        val (info, percent) = describeAssignedCredits(credits, spendControl, rateLimitReachedType)
        update("Assigned credits", info, percent)
    }

    private fun updateResetCredits(availableCount: Int, resetCredits: List<RateLimitResetCredit>) {
        resetCreditsPanel.removeAll()
        if (availableCount <= 0) {
            resetCreditsPanel.isVisible = false
            return
        }

        resetCreditsPanel.add(JLabel("Resets available: $availableCount"))
        resetCreditsPanel.add(ActionLink("Reset") { confirmAndReset(resetCredits.firstOrNull()?.creditId) }.apply {
            icon = AllIcons.Actions.Restart
            toolTipText = "Redeem one Codex reset"
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        })
        resetCreditsPanel.isVisible = true
    }

    private fun confirmAndReset(creditId: String?) {
        val result = Messages.showYesNoDialog(
            "Redeem one Codex reset credit now?",
            "Reset Codex Limits",
            "Reset",
            "Cancel",
            AllIcons.Actions.Restart,
        )
        if (result != Messages.YES) {
            return
        }

        resetCreditsPanel.components.filterIsInstance<ActionLink>().forEach { it.isEnabled = false }
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { QuotaUsageService.getInstance().consumeOpenAiResetCredit(creditId) }
                .onFailure { exception ->
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            this,
                            exception.message ?: "Reset request failed",
                            "Reset Codex Limits",
                        )
                    }
                }
        }
    }

    private fun describeAssignedCredits(
        credits: OpenAiCredits,
        spendControl: OpenAiSpendControl?,
        rateLimitReachedType: String?,
    ): Pair<String, Int> {
        when {
            credits.unlimited == true -> return "Unlimited" to 0
            rateLimitReachedType == "workspace_member_credits_depleted" -> return "Assigned credits depleted" to 100
            credits.overageLimitReached == true -> return "Overage limit reached" to 100
            spendControl?.reached == true && (spendControl.individualLimit ?: 0.0) > 0.0 -> {
                return "Individual spend limit reached ($${formatCreditsLimit(spendControl.individualLimit!!)} cap)" to 100
            }
            credits.hasCredits == false -> return "Depleted" to 100
            !credits.balance.isNullOrBlank() -> {
                val balance = formatCreditsBalance(credits.balance)
                val hints = buildList {
                    formatApproxMessages(credits.approxLocalMessages)?.let { add("~$it local messages") }
                    formatApproxMessages(credits.approxCloudMessages)?.let { add("~$it cloud messages") }
                }
                var info = "$$balance remaining"
                if (hints.isNotEmpty()) {
                    info += " (${hints.joinToString(", ")})"
                }
                return info to 0
            }
            credits.hasCredits == true -> {
                val hints = buildList {
                    formatApproxMessages(credits.approxLocalMessages)?.let { add("~$it local messages") }
                    formatApproxMessages(credits.approxCloudMessages)?.let { add("~$it cloud messages") }
                }
                val info = if (hints.isEmpty()) {
                    "Available"
                } else {
                    "Available (${hints.joinToString(", ")})"
                }
                return info to 0
            }
        }

        spendControl?.individualLimit?.takeIf { it > 0.0 }?.let { limit ->
            return "Individual limit: $${formatCreditsLimit(limit)}" to if (spendControl.reached == true) 100 else 0
        }

        return "Unknown" to 0
    }

    private fun formatCreditsBalance(balance: String): String {
        return balance.toDoubleOrNull()?.let(::formatCreditsLimit) ?: balance
    }

    private fun formatCreditsLimit(value: Double): String {
        return if (value >= 100.0) {
            value.roundToInt().toString()
        } else {
            String.format(Locale.US, "%.2f", value)
        }
    }
}
