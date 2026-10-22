package de.moritzf.quota.idea.ui.popup

import com.intellij.util.ui.JBUI
import de.moritzf.quota.github.GitHubQuota
import de.moritzf.quota.github.GitHubSubscriptionState
import de.moritzf.quota.github.GitHubUsageWindow
import de.moritzf.quota.idea.common.QuotaBundle.message
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.idea.ui.indicator.clampPercent
import de.moritzf.quota.shared.ProviderQuota
import kotlin.math.roundToInt

internal class GitHubPopupSection : ProviderPopupSection() {
    private val separator = createSeparatedBlock()
    private val errorLabel = createWarningLabel("").apply { border = JBUI.Borders.emptyTop(1) }
    private val titleLabel =
        createSectionTitleLabel("GitHub Copilot", QuotaIcons.GITHUB).apply {
            border = JBUI.Borders.emptyTop(0)
        }

    // Copilot reports up to three windows: premium requests, chat, completions.
    private val premiumBlock = WindowBlockPanel(3)
    private val chatBlock = WindowBlockPanel(5)
    private val completionsBlock = WindowBlockPanel(5)
    private val budgetBlock = WindowBlockPanel(5)

    init {
        isOpaque = false
        add(separator)
        add(errorLabel)
        add(titleLabel)
        add(premiumBlock)
        add(chatBlock)
        add(completionsBlock)
        add(budgetBlock)
        hideAll()
    }

    override fun update(quota: ProviderQuota?, error: String?, visible: Boolean) {
        updateContent(quota as? GitHubQuota, error, visible)
    }

    private fun updateContent(quota: GitHubQuota?, error: String?, visible: Boolean) {
        isVisible = visible
        if (!visible) return

        when {
            error != null -> {
                hideAll()
                errorLabel.isVisible = true
                errorLabel.text = "GitHub error: $error"
            }

            quota == null -> {
                hideAll()
                titleLabel.isVisible = true
                titleLabel.text = sectionTitle("GitHub Copilot")
                premiumBlock.showLoading("Premium requests")
            }

            else -> {
                val inactiveMessage = inactiveMessage(quota.subscriptionState)
                if (inactiveMessage != null) {
                    hideAll()
                    errorLabel.isVisible = true
                    errorLabel.text = inactiveMessage
                    titleLabel.isVisible = true
                    titleLabel.text =
                        sectionTitle("GitHub Copilot", quota.plan.takeIf { it.isNotBlank() })
                    return
                }

                val premium = quota.premiumInteractions
                val onBudget =
                    premium != null && premium.usagePercent >= 100.0 && premium.overageAvailable()
                val limitReached =
                    quota.limitedWindows().any {
                        it.usagePercent >= 100.0 && !(it === premium && onBudget)
                    }
                errorLabel.isVisible = limitReached || onBudget
                errorLabel.text =
                    when {
                        limitReached -> "GitHub Copilot limit reached"
                        onBudget -> message("quota.extra.inUse")
                        else -> ""
                    }

                titleLabel.isVisible = true
                titleLabel.text =
                    sectionTitle("GitHub Copilot", quota.plan.takeIf { it.isNotBlank() })
                bindWindow(premiumBlock, quota.premiumInteractions)
                bindWindow(chatBlock, quota.chat)
                bindWindow(completionsBlock, quota.completions)
                val budget = premium?.overageLimit?.takeIf { premium.overagePermitted && it > 0.0 }
                if (budget != null) {
                    val used = premium.overageUsed ?: 0.0
                    val percent = clampPercent((used / budget * 100.0).roundToInt())
                    budgetBlock.update(
                        message("quota.extra.title"),
                        message("quota.extra.used", formatAmount(used), formatAmount(budget)),
                        percent,
                    )
                } else {
                    budgetBlock.clear()
                }
            }
        }
    }

    private fun inactiveMessage(state: GitHubSubscriptionState): String? {
        return when (state) {
            GitHubSubscriptionState.ACTIVE -> null
            GitHubSubscriptionState.SUBSCRIPTION_ENDED -> "GitHub Copilot subscription ended"
            GitHubSubscriptionState.NO_ACTIVE_SUBSCRIPTION ->
                "No active GitHub Copilot subscription"
        }
    }

    private fun bindWindow(block: WindowBlockPanel, window: GitHubUsageWindow?) {
        if (window == null || window.unlimited) {
            block.isVisible = false
            block.clear()
            return
        }
        block.isVisible = true
        block.updateGitHub(window)
    }

    private fun hideAll() {
        errorLabel.isVisible = false
        titleLabel.isVisible = false
        premiumBlock.isVisible = false
        chatBlock.isVisible = false
        completionsBlock.isVisible = false
        budgetBlock.isVisible = false
    }

    private fun formatAmount(value: Double): String =
        if (value == Math.rint(value)) value.toLong().toString()
        else String.format(java.util.Locale.ROOT, "%.1f", value)

    private fun WindowBlockPanel.updateGitHub(window: GitHubUsageWindow) {
        val percent = clampPercent(window.usagePercent.roundToInt())
        val resetText = QuotaUiUtil.formatReset(window.resetsAt)
        var info = "$percent% used"
        if (resetText != null) info += " - $resetText"
        update(window.label, info, percent)
    }
}
