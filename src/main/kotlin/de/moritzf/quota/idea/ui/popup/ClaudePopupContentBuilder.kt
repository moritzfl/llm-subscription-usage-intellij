package de.moritzf.quota.idea.ui.popup

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import de.moritzf.quota.claude.ClaudeQuota
import de.moritzf.quota.claude.ClaudeResetChoice
import de.moritzf.quota.claude.ClaudeResetGrant
import de.moritzf.quota.claude.ClaudeResetOutcome
import de.moritzf.quota.claude.ClaudeResets
import de.moritzf.quota.claude.ClaudeUsageWindow
import de.moritzf.quota.idea.common.QuotaBundle.message
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.idea.ui.indicator.clampPercent
import de.moritzf.quota.shared.ProviderQuota
import java.awt.Cursor
import java.awt.FlowLayout
import javax.swing.JLabel
import javax.swing.JPanel
import kotlin.math.roundToInt

internal class ClaudePopupSection : ProviderPopupSection() {
    private val separator = createSeparatedBlock()
    private val errorLabel = createWarningLabel("").apply { border = JBUI.Borders.emptyTop(1) }
    private val titleLabel =
        createSectionTitleLabel("Claude", QuotaIcons.CLAUDE).apply {
            border = JBUI.Borders.emptyTop(0)
        }
    private val fiveHourBlock = WindowBlockPanel(3)
    private val weeklyBlock = WindowBlockPanel(5)
    private val modelBlock = WindowBlockPanel(5)
    private val scopedLimitsPanel =
        JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 0, true, false)).apply {
            isOpaque = false
        }
    private val routinesBlock = WindowBlockPanel(5)
    private val extraBlock = WindowBlockPanel(5)
    private val resetsPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(3), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(5)
        }

    init {
        isOpaque = false
        add(separator)
        add(errorLabel)
        add(titleLabel)
        add(fiveHourBlock)
        add(weeklyBlock)
        add(modelBlock)
        add(scopedLimitsPanel)
        add(routinesBlock)
        add(extraBlock)
        add(resetsPanel)
        hideAll()
    }

    override fun update(quota: ProviderQuota?, error: String?, visible: Boolean) {
        updateContent(quota as? ClaudeQuota, error, visible)
    }

    private fun updateContent(quota: ClaudeQuota?, error: String?, visible: Boolean) {
        isVisible = visible
        if (!visible) return

        when {
            error != null -> {
                errorLabel.isVisible = true
                errorLabel.text = "Claude error: $error"
                hideContent()
            }
            quota == null -> {
                hideAll()
                titleLabel.isVisible = true
                titleLabel.text = sectionTitle("Claude")
                fiveHourBlock.showLoading("5-hour")
                weeklyBlock.showLoading("Weekly")
            }
            else -> {
                val primary = quota.primaryWindow()
                val limitReached = quota.atLimit()
                errorLabel.isVisible = limitReached
                if (limitReached) {
                    errorLabel.text = "Claude limit reached"
                }
                titleLabel.isVisible = true
                titleLabel.text = sectionTitle("Claude", quota.plan.takeIf { it.isNotBlank() })

                quota.fiveHourUsage?.let { fiveHourBlock.updateClaude(it, "5-hour") }
                    ?: fiveHourBlock.clear()
                quota.sevenDayUsage?.let { weeklyBlock.updateClaude(it, "Weekly") }
                    ?: weeklyBlock.clear()
                val modelWindow = quota.sevenDaySonnetUsage ?: quota.sevenDayOpusUsage
                modelWindow?.let {
                    modelBlock.updateClaude(it, it.label.ifBlank { "Model weekly" })
                } ?: modelBlock.clear()
                updateScopedLimits(quota.scopedLimits)
                quota.routinesUsage?.let { routinesBlock.updateClaude(it, "Daily Routines") }
                    ?: routinesBlock.clear()
                val extra = quota.extraUsage
                if (extra?.isEnabled == true) {
                    val percent = extra.usagePercent?.roundToInt()?.let(::clampPercent) ?: 0
                    val used = extra.usedMajor
                    val limit = extra.monthlyLimitMajor
                    val currency = extra.currency?.takeIf { it.isNotBlank() } ?: "USD"
                    val money =
                        if (used != null && limit != null) {
                            String.format("%.2f / %.2f %s", used, limit, currency)
                        } else {
                            null
                        }
                    var info = "$percent% used"
                    if (money != null) info += " • $money"
                    extraBlock.update("Extra usage", info, percent)
                } else {
                    extraBlock.clear()
                }
                if (primary == null && extra?.isEnabled != true) {
                    fiveHourBlock.showLoading("Usage")
                }
                updateResets(quota.resets)
            }
        }
    }

    private fun hideAll() {
        errorLabel.isVisible = false
        hideContent()
    }

    private fun hideContent() {
        titleLabel.isVisible = false
        fiveHourBlock.isVisible = false
        weeklyBlock.isVisible = false
        modelBlock.isVisible = false
        scopedLimitsPanel.isVisible = false
        routinesBlock.isVisible = false
        extraBlock.isVisible = false
        resetsPanel.isVisible = false
    }

    private fun updateResets(resets: ClaudeResets?) {
        resetsPanel.removeAll()
        val count = resets?.availableCount() ?: 0
        if (resets == null || count <= 0) {
            resetsPanel.isVisible = false
            return
        }
        resetsPanel.add(JLabel(message("quota.claude.reset.available", count)))
        val choices = resets.choices()
        if (choices.isEmpty()) {
            resetsPanel.add(
                JLabel(message("quota.claude.reset.atLimitOnly")).apply {
                    foreground = UIUtil.getContextHelpForeground()
                    toolTipText = resetsTooltip(resets)
                }
            )
        } else {
            resetsPanel.add(
                ActionLink(message("quota.claude.reset.use")) { chooseAndReset(choices) }
                    .apply {
                        icon = AllIcons.Actions.Restart
                        toolTipText = resetsTooltip(resets)
                        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    }
            )
        }
        resetsPanel.isVisible = true
    }

    private fun resetsTooltip(resets: ClaudeResets): String {
        val lines =
            listOfNotNull(
                resets.sessionReset?.let { message("quota.claude.reset.session.detail") }
            ) + resets.offeredGrants().map(::grantDetail)
        return lines.joinToString("<br>", "<html>", "</html>") { QuotaUiUtil.escapeHtml(it) }
    }

    private fun chooseAndReset(choices: List<ClaudeResetChoice>) {
        val title = message("quota.claude.reset.dialog.title")
        val choice =
            if (choices.size == 1) {
                val answer =
                    Messages.showYesNoDialog(
                        message("quota.claude.reset.confirm", detail(choices.single())),
                        title,
                        message("quota.reset.title"),
                        "Cancel",
                        AllIcons.Actions.Restart,
                    )
                if (answer != Messages.YES) return
                choices.single()
            } else {
                val labels = choices.map(::buttonLabel)
                val buttons =
                    labels.mapIndexed { index, label ->
                        if (labels.count { it == label } > 1) "$label ${index + 1}" else label
                    } + "Cancel"
                val index =
                    Messages.showDialog(
                        message(
                            "quota.claude.reset.choose",
                            choices.joinToString("\n", transform = ::detail),
                        ),
                        title,
                        buttons.toTypedArray(),
                        buttons.lastIndex,
                        AllIcons.Actions.Restart,
                    )
                choices.getOrNull(index) ?: return
            }

        val links = resetsPanel.components.filterIsInstance<ActionLink>()
        links.forEach { it.isEnabled = false }
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching {
                val resolvedAccountId =
                    accountId
                        ?: runCatching {
                            QuotaSettingsState.getInstance()
                                .defaultAccount(QuotaProviderType.CLAUDE)
                                ?.id
                        }
                            .getOrNull()
                        ?: return@runCatching null
                QuotaUsageService.getInstance().redeemClaudeReset(choice, resolvedAccountId)
            }
                .onSuccess { outcome ->
                    if (outcome == null || outcome.succeeded) return@onSuccess
                    ApplicationManager.getApplication().invokeLater {
                        links.forEach { it.isEnabled = true }
                        Messages.showInfoMessage(this, resultMessage(outcome.result), title)
                    }
                }
                .onFailure { exception ->
                    ApplicationManager.getApplication().invokeLater {
                        links.forEach { it.isEnabled = true }
                        Messages.showErrorDialog(
                            this,
                            exception.message ?: message("quota.claude.reset.failed"),
                            title,
                        )
                    }
                }
        }
    }

    private fun buttonLabel(choice: ClaudeResetChoice): String =
        when (choice) {
            is ClaudeResetChoice.Session -> message("quota.claude.reset.session")
            is ClaudeResetChoice.Grant ->
                choice.grant.label.ifBlank { message("quota.claude.reset.grant") }
        }

    private fun detail(choice: ClaudeResetChoice): String =
        when (choice) {
            is ClaudeResetChoice.Session -> message("quota.claude.reset.session.detail")
            is ClaudeResetChoice.Grant -> grantDetail(choice.grant)
        }

    private fun grantDetail(grant: ClaudeResetGrant): String =
        listOfNotNull(
                message(
                    "quota.claude.reset.grant.detail",
                    grant.label.ifBlank { message("quota.claude.reset.grant") },
                    refilledLimits(grant.clears),
                    grant.resetsLeft,
                ),
                message("quota.claude.reset.grant.atLimitOnly").takeUnless { grant.usableNow },
                QuotaUiUtil.formatExpiry(grant.endsAt)?.let { "$it." },
            )
            .joinToString(" ")

    private fun refilledLimits(clears: List<String>): String {
        val fiveHour = "five_hour" in clears
        val weekly = clears.any { it.startsWith("seven_day") }
        return message(
            when {
                fiveHour && weekly -> "quota.claude.reset.window.both"
                fiveHour -> "quota.claude.reset.window.fiveHour"
                weekly -> "quota.claude.reset.window.weekly"
                else -> "quota.claude.reset.window.any"
            }
        )
    }

    private fun resultMessage(result: String): String =
        when (result) {
            "already_used",
            "not_limited",
            "cooldown",
            ClaudeResetOutcome.RATE_LIMITED -> message("quota.claude.reset.result.$result")
            else -> message("quota.claude.reset.result.unavailable")
        }

    private fun updateScopedLimits(limits: List<ClaudeUsageWindow>) {
        scopedLimitsPanel.removeAll()
        if (limits.isEmpty()) {
            scopedLimitsPanel.isVisible = false
            return
        }
        limits.forEach { window ->
            val block = WindowBlockPanel(5)
            block.updateClaude(window, window.label.ifBlank { "Scoped limit" })
            scopedLimitsPanel.add(block)
        }
        scopedLimitsPanel.isVisible = true
        scopedLimitsPanel.revalidate()
        scopedLimitsPanel.repaint()
    }

    private fun WindowBlockPanel.updateClaude(window: ClaudeUsageWindow, label: String) {
        val percent = clampPercent(window.usagePercent.roundToInt())
        val resetText = QuotaUiUtil.formatReset(window.resetsAt)
        var info = "$percent% used"
        if (resetText != null) info += " - $resetText"
        update(label, info, percent)
    }
}
