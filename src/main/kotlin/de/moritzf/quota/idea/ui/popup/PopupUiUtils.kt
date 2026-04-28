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
import com.intellij.ui.components.panels.NonOpaquePanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.quota.idea.settings.QuotaSettingsConfigurable
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.idea.ui.indicator.clampPercent
import de.moritzf.quota.idea.ui.indicator.scaleIconToQuotaStatusSize
import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.openai.UsageWindow
import de.moritzf.quota.opencode.OpenCodeUsageWindow
import de.moritzf.quota.ollama.OllamaUsageWindow
import org.intellij.lang.annotations.Language
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.FlowLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JProgressBar
import kotlin.math.roundToInt

internal fun createOpenSettingsButton(onOpenSettings: () -> Unit): ActionLink {
    return ActionLink("") { onOpenSettings() }.apply {
        icon = AllIcons.General.Settings
        autoHideOnDisable = false
        toolTipText = "Open settings"
        margin = JBUI.emptyInsets()
        border = JBUI.Borders.empty()
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusPainted = false
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }
}

internal fun createPopupStack(): NonOpaquePanel {
    return NonOpaquePanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 0, true, false)).apply {
        border = JBUI.Borders.empty(6, 8, 5, 8)
    }
}

internal fun createHeaderRow(onOpenSettings: () -> Unit): JComponent {
    return BorderLayoutPanel().apply {
        isOpaque = false
        addToLeft(createPopupTitleLabel())
        addToRight(createOpenSettingsButton(onOpenSettings))
    }
}

internal fun createWindowBlock(window: UsageWindow, fallbackLabel: String, top: Int): JComponent {
    val percent = clampPercent(window.usedPercent.roundToInt())
    val title = describeWindowLabel(window, fallbackLabel)
    val resetText = QuotaUiUtil.formatReset(window.resetsAt)
    var info = "$percent% used"
    if (resetText != null) {
        info += " - $resetText"
    }

    return createPopupStack().apply {
        border = JBUI.Borders.emptyTop(top)
        add(createWindowTitleLabel(title))
        add(withVerticalInsets(JBLabel(info), top = 1))
        add(withVerticalInsets(createUsageProgressBar(percent), top = 1))
    }
}

internal fun createLoadingWindowBlock(label: String, top: Int): JComponent {
    return createPopupStack().apply {
        border = JBUI.Borders.emptyTop(top)
        add(createWindowTitleLabel("$label limit"))
        add(withVerticalInsets(createMutedLabel("Loading usage..."), top = 1))
        add(withVerticalInsets(createUsageProgressBar(0), top = 1))
    }
}

internal fun createOpenCodeWindowBlock(window: OpenCodeUsageWindow, label: String, top: Int): JComponent {
    val percent = clampPercent(window.usagePercent)
    val resetText = QuotaUiUtil.formatResetInSeconds(window.resetInSec)
    var info = "$percent% used"
    if (window.isRateLimited) {
        info += " - LIMIT REACHED"
    }
    if (resetText != null) {
        info += " - $resetText"
    }

    return createPopupStack().apply {
        border = JBUI.Borders.emptyTop(top)
        add(createWindowTitleLabel("$label limit"))
        add(withVerticalInsets(JBLabel(info), top = 1))
        add(withVerticalInsets(createUsageProgressBar(percent), top = 1))
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
    ApplicationManager.getApplication().invokeLater(
        {
            beforeOpen()
            ShowSettingsUtil.getInstance().showSettingsDialog(project, QuotaSettingsConfigurable::class.java)
        },
        modality,
    )
}

internal fun describeWindowLabel(window: UsageWindow, fallbackLabel: String): String {
    val minutes = window.windowDuration?.toMinutes() ?: return "$fallbackLabel limit"
    return when {
        minutes in 295L..305L -> "5h limit"
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

    return when {
        quota.limitReached == true -> "Codex limit reached"
        quota.allowed == false -> "Codex usage not allowed"
        quota.reviewLimitReached == true -> "Code review limit reached"
        quota.reviewAllowed == false -> "Code review usage not allowed"
        else -> null
    }
}

@Language("RegExp")
private const val WHITESPACE_REGEX = "\\s+"

internal fun String.toDisplayLabel(): String {
    return split(Regex(WHITESPACE_REGEX)).joinToString(" ") { word ->
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

internal fun createWindowTitleLabel(text: String): JBLabel {
    return JBLabel(text).apply {
        font = font.deriveFont(font.style or Font.BOLD)
    }
}

internal fun createSectionTitleLabel(text: String, icon: Icon? = null): JBLabel {
    return JBLabel(text).apply {
        if (icon != null) {
            this.icon = scaleIconToQuotaStatusSize(icon, this)
            iconTextGap = JBUI.scale(4)
        }
        foreground = JBColor.BLUE
        font = font.deriveFont(font.style or Font.BOLD, font.size + 1f)
    }
}

internal fun createWarningLabel(text: String): JBLabel {
    return JBLabel(text).apply {
        foreground = JBColor.RED
        font = font.deriveFont(font.style or Font.BOLD)
    }
}

internal fun createMutedLabel(text: String): JBLabel {
    return JBLabel(text).apply {
        foreground = JBColor.GRAY
    }
}

internal fun createUpdatedAtRow(items: List<UpdatedAtItem>): JComponent {
    return JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(3), 0)).apply {
        isOpaque = false
        add(createMutedLabel("Updated:"))
        items.forEachIndexed { index, item ->
            item.icons.forEach { providerIcon ->
                add(JBLabel().apply {
                    icon = scaleIconToQuotaStatusSize(providerIcon.icon, this)
                    toolTipText = providerIcon.label
                })
            }
            add(createMutedLabel(item.text))
            if (index < items.lastIndex) {
                add(createMutedLabel(";"))
            }
        }
    }
}

internal data class UpdatedAtItem(
    val icons: List<UpdatedAtIcon>,
    val text: String,
)

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

internal fun createOllamaWindowBlock(window: OllamaUsageWindow, label: String, top: Int): JComponent {
    val percent = clampPercent(window.usagePercent.roundToInt())
    val resetText = QuotaUiUtil.formatReset(window.resetsAt)
    var info = "$percent% used"
    if (resetText != null) {
        info += " - $resetText"
    }

    return createPopupStack().apply {
        border = JBUI.Borders.emptyTop(top)
        add(createWindowTitleLabel("$label limit"))
        add(withVerticalInsets(JBLabel(info), top = 1))
        add(withVerticalInsets(createUsageProgressBar(percent), top = 1))
    }
}

internal fun createCompactSeparator(): JComponent {
    val separatorColor = JBUI.CurrentTheme.Popup.separatorColor()
    return SeparatorComponent(1, 0, separatorColor, separatorColor)
}
