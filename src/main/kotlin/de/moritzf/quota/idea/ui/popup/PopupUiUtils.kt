package de.moritzf.quota.idea.ui.popup

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.ui.JBColor
import com.intellij.ui.SeparatorComponent
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.quota.idea.settings.QuotaSettingsConfigurable
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.ui.indicator.scaleIconToQuotaStatusSize
import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.openai.UsageWindow
import de.moritzf.quota.openai.creditsLimitWarning
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.time.Duration
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.SwingUtilities
import kotlin.time.Instant
import org.intellij.lang.annotations.Language

internal fun resetTokenTooltip(
    action: String,
    expirations: List<Instant?>,
    availableCount: Int = expirations.size,
): String {
    return buildString {
        append("<html>")
        append(QuotaUiUtil.escapeHtml(action))
        repeat(maxOf(availableCount, expirations.size)) { index ->
            append("<br>Reset ${index + 1}: ")
            append(
                QuotaUiUtil.escapeHtml(
                    QuotaUiUtil.formatExpiry(expirations.getOrNull(index)) ?: "Expiration unknown"
                )
            )
        }
        append("</html>")
    }
}

internal fun createOpenSettingsButton(onOpenSettings: () -> Unit): ActionLink {
    return ActionLink("") { onOpenSettings() }
        .apply {
            icon = AllIcons.General.Settings
            autoHideOnDisable = false
            toolTipText = "Open settings"
            accessibleContext.accessibleName = "Open settings"
            margin = JBUI.emptyInsets()
            border = JBUI.Borders.empty()
            isBorderPainted = false
            isContentAreaFilled = false
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
}

internal fun createHeaderRow(
    title: JBLabel = createPopupTitleLabel(),
    onOpenSettings: () -> Unit,
): JComponent {
    return BorderLayoutPanel().apply {
        isOpaque = false
        addToLeft(title)
        addToRight(createOpenSettingsButton(onOpenSettings))
    }
}

internal fun createSeparatedBlock(): JComponent {
    return withVerticalInsets(createCompactSeparator(), top = 5, bottom = 5)
}

internal fun withVerticalInsets(component: JComponent, top: Int = 0, bottom: Int = 0): JComponent {
    return BorderLayoutPanel().apply {
        isOpaque = false
        border = JBUI.Borders.empty(top, 0, bottom, 0)
        addToCenter(component)
    }
}

internal fun openSettings(project: Project, component: Component, beforeOpen: () -> Unit = {}) {
    if (project.isDisposed) {
        return
    }

    val modality = ModalityState.stateForComponent(component)
    ApplicationManager.getApplication()
        .invokeLater(
            {
                beforeOpen()
                ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, QuotaSettingsConfigurable::class.java)
            },
            modality,
        )
}

internal fun describeWindowLabel(window: UsageWindow, fallbackLabel: String): String {
    return describeDurationLimitLabel(window.windowDuration, fallbackLabel)
}

internal fun describeDurationLimitLabel(duration: Duration?, fallbackLabel: String): String {
    val minutes = duration?.toMinutes() ?: return "$fallbackLabel limit"
    return when {
        minutes in 295L..305L -> "5h limit"
        minutes in 43190L..43210L -> "Monthly limit"
        minutes in 10070L..10090L -> "Weekly limit"
        minutes % (60L * 24L * 7L) == 0L -> {
            val weeks = minutes / (60L * 24L * 7L)
            if (weeks == 1L) "Weekly limit" else "${weeks}w limit"
        }

        minutes % (60L * 24L) == 0L -> "${minutes / (60L * 24L)}d limit"
        minutes % 60L == 0L -> "${minutes / 60L}h limit"
        else -> "${minutes}m limit"
    }
}

internal fun getLimitWarning(quota: OpenAiCodexQuota?): String? {
    if (quota == null) {
        return null
    }

    quota.creditsLimitWarning()?.let {
        return it
    }

    return when {
        quota.limitReached == true -> "Codex limit reached"
        quota.reviewLimitReached == true -> "Code review limit reached"
        else -> null
    }
}

@Language("RegExp") private const val PLAN_WORD_REGEX = "[_\\s]+"

@Language("RegExp") private const val SELF_SERVE_PREFIX_REGEX = "(?i)^self_serve[_\\s]*"

/**
 * The plan name Codex shows in /status for a ChatGPT plan type, such as "Pro 100" for `prolite`;
 * unknown types are title-cased.
 */
internal fun codexPlanLabel(planType: String): String =
    when (planType.trim().lowercase()) {
        "free" -> "Free"
        "go" -> "Go"
        "plus" -> "Plus"
        "pro" -> "Pro 200"
        "prolite" -> "Pro 100"
        "promax" -> "Pro 500"
        "team",
        "self_serve_business_usage_based" -> "Business"
        "self_serve_business_prolite" -> "Business Premium"
        "business",
        "ent26",
        "enterprise",
        "hc",
        "enterprise_cbp_usage_based" -> "Enterprise"
        "enterprise_cbp_automation" -> "Enterprise (Automation)"
        "edu",
        "education" -> "Edu"
        "edu_plus" -> "Edu Plus"
        "edu_pro" -> "Edu Pro"
        "sci" -> "Academic Researchers"
        "sci_plus" -> "Academic Researchers Plus"
        else -> planType.toDisplayLabel()
    }

internal fun String.toDisplayLabel(): String {
    return trim()
        .replace(Regex(SELF_SERVE_PREFIX_REGEX), "")
        .split(Regex(PLAN_WORD_REGEX))
        .filter { it.isNotEmpty() }
        .joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { character ->
                if (character.isLowerCase()) character.titlecase() else character.toString()
            }
        }
}

internal fun createPopupTitleLabel(): JBLabel {
    return JBLabel("Subscription Usage").apply {
        font = font.deriveFont(font.style or Font.BOLD, font.size + 2f)
    }
}

internal fun createWindowTitleLabel(text: String): TruncatingPopupLabel {
    return TruncatingPopupLabel(text).apply { font = font.deriveFont(font.style or Font.BOLD) }
}

internal fun createSectionTitleLabel(text: String, icon: Icon? = null): JBLabel {
    return TruncatingPopupLabel(text).apply {
        if (icon != null) {
            this.icon = scaleIconToQuotaStatusSize(icon, this)
            iconTextGap = JBUI.scale(4)
        }
        foreground = JBColor.BLUE
        font = font.deriveFont(font.style or Font.BOLD, font.size + 1f)
    }
}

internal fun createWarningLabel(text: String): TruncatingPopupLabel {
    return TruncatingPopupLabel(text).apply {
        foreground = JBColor.RED
        font = font.deriveFont(font.style or Font.BOLD)
    }
}

internal fun createMutedLabel(text: String): JBLabel {
    return JBLabel(text).apply { foreground = JBColor.GRAY }
}

internal data class UpdatedAtItem(
    val icons: List<UpdatedAtIcon>,
    val text: String,
)

internal fun groupUpdatedAtItems(items: List<UpdatedAtItem>): List<UpdatedAtItem> {
    return items
        .groupBy { it.text }
        .map { (text, group) -> UpdatedAtItem(icons = group.flatMap { it.icons }, text = text) }
}

internal data class UpdatedAtIcon(
    val label: String,
    val icon: Icon,
)

internal fun createUsageProgressBar(percent: Int): JProgressBar {
    return JProgressBar(0, 100).apply {
        value = percent
        isStringPainted = false
        preferredSize = Dimension(200, 4)
    }
}

internal fun createCompactSeparator(): JComponent {
    val separatorColor = JBUI.CurrentTheme.Popup.separatorColor()
    return SeparatorComponent(1, 0, separatorColor, separatorColor)
}

/**
 * Reusable panel for a single quota window block (title, info, progress bar). Creates its child
 * components once and updates them in-place to avoid popup flicker.
 */
internal class WindowBlockPanel(topInset: Int = 3) :
    JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 0, true, false)) {
    private val titleLabel = createWindowTitleLabel("")
    private val infoLabel = TruncatingPopupLabel("").apply { border = JBUI.Borders.emptyTop(1) }
    private val progressBar = createUsageProgressBar(0).apply { border = JBUI.Borders.emptyTop(1) }

    init {
        isOpaque = false
        border = JBUI.Borders.emptyTop(topInset)
        add(titleLabel)
        add(infoLabel)
        add(progressBar)
    }

    fun update(title: String, info: String, percent: Int, detailTooltip: String? = null) {
        titleLabel.text = title
        if (!titleLabel.font.isBold) titleLabel.font = titleLabel.font.deriveFont(Font.BOLD)
        infoLabel.text = info
        infoLabel.detailTooltip = detailTooltip
        infoLabel.isVisible = true
        progressBar.value = percent
        progressBar.toolTipText = detailTooltip
        progressBar.isVisible = true
        isVisible = true
    }

    fun showUnavailable(title: String, info: String, detailTooltip: String? = null) {
        update(title, info, 0, detailTooltip)
        progressBar.isVisible = false
    }

    fun showTitleOnly(title: String) {
        update(title, "", 0)
        titleLabel.font = titleLabel.font.deriveFont(Font.PLAIN)
        infoLabel.isVisible = false
        progressBar.isVisible = false
    }

    fun showLoading(title: String) {
        update("$title limit", "Loading usage...", 0)
    }

    fun clear() {
        isVisible = false
    }
}

/** Shows optional details and any clipped label text on hover. */
internal class TruncatingPopupLabel(initialText: String = "") : JBLabel(initialText) {
    var detailTooltip: String? = null
        set(value) {
            field = value
            updateTruncationTooltip()
        }

    init {
        addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent?) {
                    updateTruncationTooltip()
                }

                override fun componentShown(e: ComponentEvent?) {
                    updateTruncationTooltip()
                }
            }
        )
    }

    override fun setText(text: String?) {
        super.setText(text)
        SwingUtilities.invokeLater { updateTruncationTooltip() }
    }

    private fun updateTruncationTooltip() {
        val fullText = text.orEmpty()
        val clippedText = fullText.takeIf {
            fullText.isNotBlank() && isLabelTextTruncated(this, fullText)
        }
        toolTipText =
            when {
                detailTooltip == null -> clippedText
                clippedText == null -> detailTooltip
                else ->
                    "<html>${QuotaUiUtil.escapeHtml(clippedText)}<br>${QuotaUiUtil.escapeHtml(detailTooltip.orEmpty())}</html>"
            }
    }
}

internal fun isLabelTextTruncated(label: JBLabel, fullText: String): Boolean {
    if (!label.isShowing || label.width <= 0) {
        return false
    }
    val availableWidth =
        label.width -
            label.insets.left -
            label.insets.right -
            (label.icon?.let { it.iconWidth + label.iconTextGap } ?: 0)
    if (availableWidth <= 0) {
        return false
    }
    return label.getFontMetrics(label.font).stringWidth(fullText) > availableWidth
}
