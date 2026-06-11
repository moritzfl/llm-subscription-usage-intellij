package de.moritzf.quota.idea.ui.popup

import de.moritzf.quota.github.GitHubQuota
import de.moritzf.quota.github.GitHubUsageWindow
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.idea.ui.indicator.clampPercent
import kotlin.math.roundToInt
import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.util.ui.JBUI
import javax.swing.JPanel

internal class GitHubPopupSection : JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 0, true, false)) {
    private val separator = createSeparatedBlock()
    private val errorLabel = createWarningLabel("").apply { border = JBUI.Borders.emptyTop(1) }
    private val titleLabel =
        createSectionTitleLabel("GitHub Copilot", QuotaIcons.GITHUB).apply { border = JBUI.Borders.emptyTop(0) }

    // Copilot reports up to three windows: premium requests, chat, completions.
    private val premiumBlock = WindowBlockPanel(3)
    private val chatBlock = WindowBlockPanel(5)
    private val completionsBlock = WindowBlockPanel(5)

    init {
        isOpaque = false
        add(separator)
        add(errorLabel)
        add(titleLabel)
        add(premiumBlock)
        add(chatBlock)
        add(completionsBlock)
        hideAll()
    }

    fun update(quota: GitHubQuota?, error: String?, visible: Boolean) {
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
                titleLabel.text = "GitHub Copilot"
                premiumBlock.showLoading("Premium requests")
            }

            else -> {
                val limitReached = quota.limitedWindows().any { it.usagePercent >= 100.0 }
                errorLabel.isVisible = limitReached
                if (limitReached) {
                    errorLabel.text = "GitHub Copilot limit reached"
                }

                titleLabel.isVisible = true
                titleLabel.text = quota.plan.ifBlank { "GitHub Copilot" }
                bindWindow(premiumBlock, quota.premiumInteractions)
                bindWindow(chatBlock, quota.chat)
                bindWindow(completionsBlock, quota.completions)
            }
        }
    }

    private fun bindWindow(block: WindowBlockPanel, window: GitHubUsageWindow?) {
        if (window == null) {
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
    }

    private fun WindowBlockPanel.updateGitHub(window: GitHubUsageWindow) {
        if (window.unlimited) {
            update(window.label, "unlimited", 0)
            return
        }
        val percent = clampPercent(window.usagePercent.roundToInt())
        val resetText = QuotaUiUtil.formatReset(window.resetsAt)
        var info = "$percent% used"
        if (resetText != null) info += " - $resetText"
        update(window.label, info, percent)
    }
}
