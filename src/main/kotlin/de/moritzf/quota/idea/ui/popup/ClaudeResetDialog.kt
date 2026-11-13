package de.moritzf.quota.idea.ui.popup

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import de.moritzf.quota.claude.ClaudeResetChoice
import de.moritzf.quota.claude.ClaudeResets
import de.moritzf.quota.idea.common.QuotaBundle.message
import de.moritzf.quota.idea.ui.QuotaUiUtil
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.ScrollPaneConstants

/** Show each grant's expiry without repeating its potentially long provider-supplied name. */
internal fun claudeResetsTooltip(resets: ClaudeResets): String {
    val lines = buildList {
        add(message("quota.claude.reset.summary.title"))
        var grantNumber = 0
        resetChoices(resets).forEach { choice ->
            when (choice) {
                is ClaudeResetChoice.Session -> add(message("quota.claude.reset.summary.session"))
                is ClaudeResetChoice.Grant -> {
                    val grant = choice.grant
                    val details =
                        listOfNotNull(
                                refilledLimits(grant.clears),
                                message("quota.claude.reset.summary.remaining", grant.resetsLeft)
                                    .takeIf { grant.resetsLeft > 1 },
                                QuotaUiUtil.formatExpiry(grant.endsAt)
                                    ?: message("quota.claude.reset.summary.expiryUnknown"),
                                message("quota.claude.reset.summary.atLimit").takeUnless {
                                    grant.usableNow
                                },
                            )
                            .joinToString(" · ")
                    add(message("quota.claude.reset.summary.grant", ++grantNumber, details))
                }
            }
        }
    }
    return lines.joinToString("<br>", "<html>", "</html>") { QuotaUiUtil.escapeHtml(it) }
}

private fun resetChoices(resets: ClaudeResets): List<ClaudeResetChoice> =
    resets.choices() +
        resets.offeredGrants().filterNot { it.usableNow }.map(ClaudeResetChoice::Grant)

internal fun refilledLimits(clears: List<String>): String {
    val fiveHour = "five_hour" in clears
    val weekly = clears.any { it.startsWith("seven_day") }
    return message(
        when {
            fiveHour && weekly -> "quota.claude.reset.window.both"
            fiveHour -> "quota.claude.reset.window.fiveHour"
            weekly -> "quota.claude.reset.window.weekly"
            else -> "quota.claude.reset.window.any"
        },
    )
}

/** Show restricted grants too, but never select them or preselect an irreversible action. */
internal class ClaudeResetSelectionPanel(
    resets: ClaudeResets,
    private val onSelection: () -> Unit = {},
) : JPanel(BorderLayout()) {
    private val choices = resetChoices(resets)
    private val buttons = mutableListOf<JRadioButton>()
    private val group = ButtonGroup()

    val selectedChoice: ClaudeResetChoice?
        get() = buttons.indexOfFirst { it.isSelected }.takeIf { it >= 0 }?.let(choices::get)

    val firstChoice: JComponent?
        get() = buttons.firstOrNull { it.isEnabled }

    init {
        val list = JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 0, true, false))
        list.isOpaque = false
        var grantNumber = 0
        choices.forEach { choice ->
            val number = if (choice is ClaudeResetChoice.Grant) ++grantNumber else 0
            val button =
                JRadioButton(choiceHtml(choice, resets.nextGrantId, number)).apply {
                    isOpaque = false
                    isEnabled = choice !is ClaudeResetChoice.Grant || choice.grant.usableNow
                    border = JBUI.Borders.empty(6, 2)
                    addActionListener { onSelection() }
                }
            group.add(button)
            buttons.add(button)
            list.add(button)
        }
        add(JLabel(message("quota.claude.reset.choose")), BorderLayout.NORTH)
        add(
            JBScrollPane(list).apply {
                preferredSize = JBUI.size(540, minOf(choices.size * 105 + 10, 360))
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                border = JBUI.Borders.empty(8, 0)
            },
            BorderLayout.CENTER,
        )
        val notice = JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 0, true, false))
        notice.isOpaque = false
        if (resets.offeredGrants().any { !it.usableNow }) {
            notice.add(JLabel(message("quota.claude.reset.restrictedNotice")))
        }
        notice.add(JLabel(message("quota.claude.reset.irreversible")))
        add(notice, BorderLayout.SOUTH)
    }
}

private fun choiceHtml(choice: ClaudeResetChoice, nextGrantId: String?, number: Int): String {
    val title: String
    val detail: String
    when (choice) {
        is ClaudeResetChoice.Session -> {
            title = message("quota.claude.reset.session")
            detail = message("quota.claude.reset.session.description")
        }
        is ClaudeResetChoice.Grant -> {
            val grant = choice.grant
            title =
                message(
                    "quota.claude.reset.choice.title",
                    number,
                    grant.label.ifBlank { message("quota.claude.reset.grant") },
                )
            detail =
                listOfNotNull(
                        message(
                            "quota.claude.reset.choice.detail",
                            refilledLimits(grant.clears),
                            grant.resetsLeft,
                        ),
                        QuotaUiUtil.formatExpiry(grant.endsAt),
                        message("quota.claude.reset.choice.restricted").takeUnless {
                            grant.usableNow
                        },
                        message("quota.claude.reset.recommended").takeIf {
                            grant.id == nextGrantId
                        },
                    )
                    .joinToString(" · ")
        }
    }
    return "<html><table width='430'><tr><td><b>${QuotaUiUtil.escapeHtml(title)}</b><br>" +
        "${QuotaUiUtil.escapeHtml(detail)}</td></tr></table></html>"
}

internal class ClaudeResetDialog(parent: Component, resets: ClaudeResets) :
    DialogWrapper(parent, true) {
    private val selection = ClaudeResetSelectionPanel(resets) { isOKActionEnabled = true }
    val selectedChoice: ClaudeResetChoice?
        get() = selection.selectedChoice

    init {
        title = message("quota.claude.reset.dialog.title")
        setOKButtonText(message("quota.reset.title"))
        init()
        isOKActionEnabled = false
    }

    override fun createCenterPanel(): JComponent = selection

    override fun getPreferredFocusedComponent(): JComponent? = selection.firstChoice
}
