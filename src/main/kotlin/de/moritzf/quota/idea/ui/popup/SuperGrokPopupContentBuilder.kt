package de.moritzf.quota.idea.ui.popup

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.JBUI
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.idea.ui.indicator.clampPercent
import de.moritzf.quota.shared.ProviderQuota
import de.moritzf.quota.supergrok.SuperGrokQuota
import de.moritzf.quota.supergrok.SuperGrokResetToken
import de.moritzf.quota.supergrok.SuperGrokUsageWindow
import java.awt.Cursor
import java.awt.FlowLayout
import javax.swing.JLabel
import javax.swing.JPanel
import kotlin.math.roundToInt

internal class SuperGrokPopupSection : ProviderPopupSection() {
    private val separator = createSeparatedBlock()
    private val errorLabel = createWarningLabel("").apply { border = JBUI.Borders.emptyTop(1) }
    private val titleLabel =
        createSectionTitleLabel("SuperGrok", QuotaIcons.SUPERGROK).apply {
            border = JBUI.Borders.emptyTop(0)
        }
    private val blocks = listOf(WindowBlockPanel(3), WindowBlockPanel(5))
    private val resetTokensPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(3), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(5)
        }

    init {
        isOpaque = false
        add(separator)
        add(errorLabel)
        add(titleLabel)
        blocks.forEach(::add)
        add(resetTokensPanel)
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
                titleLabel.text = sectionTitle("SuperGrok")
                blocks.getOrNull(0)?.showLoading("Monthly credits")
            }
            else -> {
                val usage = quota.creditUsage
                val limitReached = usage?.isExhausted() == true
                errorLabel.isVisible = limitReached
                if (limitReached) {
                    errorLabel.text = "SuperGrok limit reached"
                }
                titleLabel.isVisible = true
                titleLabel.text = sectionTitle("SuperGrok", quota.plan.takeIf { it.isNotBlank() })
                blocks.forEach { it.clear() }
                if (usage != null) {
                    blocks[0].updateSuperGrok(usage)
                }
                quota.onDemandCap
                    ?.takeIf { it > 0 }
                    ?.let { cap -> blocks.getOrNull(1)?.update("Pay as you go", "Cap $cap", 0) }
                updateResetTokens(quota.resetTokens)
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
        resetTokensPanel.isVisible = false
    }

    private fun updateResetTokens(resetTokens: List<SuperGrokResetToken>) {
        resetTokensPanel.removeAll()
        if (resetTokens.isEmpty()) {
            resetTokensPanel.isVisible = false
            return
        }
        resetTokensPanel.add(JLabel("Resets available: ${resetTokens.size}"))
        resetTokensPanel.add(
            ActionLink("Reset") { confirmAndReset(resetTokens.first().tokenId) }
                .apply {
                    icon = AllIcons.Actions.Restart
                    toolTipText =
                        resetTokenTooltip(
                            "Redeem one SuperGrok weekly reset",
                            resetTokens.map { it.expiresAt },
                        )
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                }
        )
        resetTokensPanel.isVisible = true
    }

    private fun confirmAndReset(tokenId: String) {
        val result =
            Messages.showYesNoDialog(
                "Redeem one SuperGrok weekly reset now?",
                "Reset SuperGrok Limits",
                "Reset",
                "Cancel",
                AllIcons.Actions.Restart,
            )
        if (result != Messages.YES) {
            return
        }
        resetTokensPanel.components.filterIsInstance<ActionLink>().forEach { it.isEnabled = false }
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching {
                val resolvedAccountId =
                    accountId
                        ?: runCatching {
                            de.moritzf.quota.idea.settings.QuotaSettingsState.getInstance()
                                .defaultAccount(
                                    de.moritzf.quota.idea.common.QuotaProviderType.SUPERGROK
                                )
                                ?.id
                        }
                            .getOrNull()
                        ?: return@runCatching
                QuotaUsageService.getInstance().consumeSuperGrokReset(tokenId, resolvedAccountId)
            }
                .onFailure { exception ->
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            this,
                            exception.message ?: "Reset request failed",
                            "Reset SuperGrok Limits",
                        )
                    }
                }
        }
    }

    private fun WindowBlockPanel.updateSuperGrok(window: SuperGrokUsageWindow) {
        val percent = clampPercent(window.usagePercent.roundToInt())
        val resetText = QuotaUiUtil.formatReset(window.resetsAt)
        var info = "$percent% used"
        if (resetText != null) info += " - $resetText"
        update(window.label.ifBlank { "Credits" }, info, percent)
    }
}
