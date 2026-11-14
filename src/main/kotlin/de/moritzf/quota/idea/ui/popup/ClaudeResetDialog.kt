package de.moritzf.quota.idea.ui.popup

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.panel
import de.moritzf.quota.claude.ClaudeResetChoice
import de.moritzf.quota.claude.ClaudeResetGrant
import de.moritzf.quota.claude.ClaudeResetKind
import de.moritzf.quota.claude.ClaudeResets
import de.moritzf.quota.idea.common.QuotaBundle.message
import de.moritzf.quota.idea.ui.QuotaUiUtil
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRadioButton
import kotlin.time.Clock
import kotlin.time.toJavaDuration

/** One line per kind of reset, named as in the chooser; grant names stay in the chooser. */
internal fun claudeResetsTooltip(resets: ClaudeResets): String {
    val lines = buildList {
        add(message("quota.claude.reset.summary.title"))
        resets.offeredChoices().forEach { choice ->
            when (choice) {
                is ClaudeResetChoice.Session -> add(message("quota.claude.reset.summary.session"))
                is ClaudeResetChoice.Grant -> {
                    val details =
                        listOfNotNull(
                                refilledLimits(choice.grant),
                                message("quota.claude.reset.summary.remaining", choice.resetsLeft)
                                    .takeIf { choice.resetsLeft > 1 },
                                expiry(choice.grants),
                                message("quota.claude.reset.summary.atLimit").takeUnless {
                                    choice.grant.usableNow
                                },
                            )
                            .joinToString(" · ")
                    add(message("quota.claude.reset.summary.grant", kindName(choice), details))
                }
            }
        }
    }
    return lines.joinToString("<br>", "<html>", "</html>") { QuotaUiUtil.escapeHtml(it) }
}

/** The limits a grant refills, worded as Claude Code does, such as "5-hour and Fable limits". */
internal fun refilledLimits(grant: ClaudeResetGrant): String {
    val clears = grant.clears
    val names = buildList {
        if ("five_hour" in clears) add("fiveHour")
        if (
            "seven_day" in clears ||
                "seven_day_overage_included" in clears && !grant.givesBackWeekly
        ) {
            add("weekly")
        }
        if (grant.givesBackWeekly) add("fable")
        if ("seven_day_opus" in clears) add("opus")
        if ("seven_day_sonnet" in clears) add("sonnet")
    }
        .map { message("quota.claude.reset.window.$it") }
    return when (names.size) {
        0 -> message("quota.claude.reset.window.any")
        1 -> message("quota.claude.reset.window.one", names.single())
        else ->
            message(
                "quota.claude.reset.window.many",
                names.dropLast(1).joinToString(", "),
                names.last(),
            )
    }
}

private fun kindName(choice: ClaudeResetChoice.Grant): String =
    message(
        when (choice.grant.kind) {
            ClaudeResetKind.SESSION -> "quota.claude.reset.kind.session"
            ClaudeResetKind.WEEKLY -> "quota.claude.reset.kind.weekly"
            ClaudeResetKind.FULL -> "quota.claude.reset.kind.full"
            ClaudeResetKind.FABLE -> "quota.claude.reset.kind.fable"
        },
    )

private fun expiry(grants: List<ClaudeResetGrant>): String {
    val unknown = message("quota.claude.reset.summary.expiryUnknown")
    if (grants.size == 1) return QuotaUiUtil.formatExpiry(grants.single().endsAt) ?: unknown
    val earliest = grants.mapNotNull { it.endsAt }.minOrNull() ?: return unknown
    val remaining =
        QuotaUiUtil.formatCompactDuration((earliest - Clock.System.now()).toJavaDuration())
            ?: return QuotaUiUtil.formatExpiry(earliest) ?: unknown
    val key =
        if (grants.all { it.endsAt == earliest }) "quota.claude.reset.expires.all"
        else "quota.claude.reset.expires.first"
    return message(key, remaining)
}

/** The usable choice whose resets run out before every other one, if that is certain. */
private fun expiringFirst(choices: List<ClaudeResetChoice>): ClaudeResetChoice.Grant? {
    val usable = choices.filterIsInstance<ClaudeResetChoice.Grant>().filter { it.grant.usableNow }
    if (usable.size < 2) return null
    val earliest = usable.map { choice -> choice.grants.mapNotNull { it.endsAt }.minOrNull() }
    if (earliest.any { it == null }) return null
    val first = earliest.withIndex().minBy { it.value!! }
    return usable[first.index].takeIf { earliest.count { it == first.value } == 1 }
}

/**
 * One choice per kind of reset, as claude.ai lists them. Restricted resets are shown but never
 * selectable, and nothing is preselected because using a reset is irreversible.
 */
internal class ClaudeResetSelectionPanel(
    resets: ClaudeResets,
    private val onSelection: () -> Unit = {},
) : JPanel(BorderLayout()) {
    private val choices = resets.offeredChoices()
    private val buttons = mutableListOf<JRadioButton>()

    val selectedChoice: ClaudeResetChoice?
        get() = buttons.indexOfFirst { it.isSelected }.takeIf { it >= 0 }?.let(choices::get)

    val firstChoice: JComponent?
        get() = buttons.firstOrNull { it.isEnabled }

    init {
        isOpaque = false
        val expiringFirst = expiringFirst(choices)
        val content = panel {
            buttonsGroup(message("quota.claude.reset.choose")) {
                choices.forEach { choice ->
                    row {
                            val option =
                                radioButton(title(choice))
                                    .enabled(
                                        choice !is ClaudeResetChoice.Grant ||
                                            choice.grant.usableNow,
                                    )
                                    .comment(comment(choice), MAX_LINE_LENGTH_WORD_WRAP)
                                    .applyToComponent {
                                        toolTipText = sources(choice)
                                        addActionListener { onSelection() }
                                    }
                            buttons += option.component
                            if (choice === expiringFirst) {
                                option.gap(RightGap.SMALL)
                                label(message("quota.claude.reset.expiresFirst")).applyToComponent {
                                    icon = AllIcons.Vcs.History
                                }
                            }
                        }
                        .layout(RowLayout.INDEPENDENT)
                }
            }
            row { label(message("quota.claude.reset.irreversible")) }
        }
        content.isOpaque = false
        add(content, BorderLayout.CENTER)
    }

    private fun title(choice: ClaudeResetChoice): String =
        when (choice) {
            is ClaudeResetChoice.Session -> message("quota.claude.reset.session")
            is ClaudeResetChoice.Grant ->
                if (choice.resetsLeft > 1) {
                    message("quota.claude.reset.choice.count", kindName(choice), choice.resetsLeft)
                } else {
                    kindName(choice)
                }
        }

    private fun comment(choice: ClaudeResetChoice): String {
        val lines =
            when (choice) {
                is ClaudeResetChoice.Session ->
                    listOf(
                        message("quota.claude.reset.session.description"),
                        message("quota.claude.reset.session.frequency"),
                    )
                is ClaudeResetChoice.Grant -> {
                    val grant = choice.grant
                    val effect =
                        message(
                            if (grant.givesBackWeekly) "quota.claude.reset.choice.givesBackWeekly"
                            else "quota.claude.reset.choice.refills",
                            refilledLimits(grant),
                        )
                    val availability =
                        listOfNotNull(
                                message("quota.claude.reset.choice.restricted").takeUnless {
                                    grant.usableNow
                                },
                                expiry(choice.grants),
                            )
                            .joinToString(" · ")
                    listOf(effect, availability)
                }
            }
        return lines.joinToString("<br>") { QuotaUiUtil.escapeHtml(it) }
    }

    /** Provider-supplied grant names, which can be long and repetitive, on hover only. */
    private fun sources(choice: ClaudeResetChoice): String? {
        if (choice !is ClaudeResetChoice.Grant) return null
        val lines =
            choice.grants
                .filter { it.label.isNotBlank() }
                .map { grant ->
                    listOfNotNull(
                            grant.label,
                            message("quota.claude.reset.summary.remaining", grant.resetsLeft)
                                .takeIf { grant.resetsLeft > 1 },
                            QuotaUiUtil.formatExpiry(grant.endsAt),
                        )
                        .joinToString(" · ")
                }
        if (lines.isEmpty()) return null
        return lines.joinToString("<br>", "<html>", "</html>") { QuotaUiUtil.escapeHtml(it) }
    }
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
