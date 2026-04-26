package de.moritzf.quota.idea

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.SeparatorComponent
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.NonOpaquePanel
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.quota.OpenAiCodexQuota
import de.moritzf.quota.OpenCodeQuota
import de.moritzf.quota.OpenCodeUsageWindow
import de.moritzf.quota.UsageWindow
import org.intellij.lang.annotations.Language
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Point
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JProgressBar
import kotlin.math.roundToInt

internal enum class QuotaPopupLocation {
    ABOVE,
    BELOW,
}

internal object QuotaPopupSupport {
    private const val OPENCODE_GO_LABEL = "OpenCode Go"
    fun showPopup(
        project: Project,
        component: Component,
        quota: OpenAiCodexQuota?,
        error: String?,
        openCodeQuota: OpenCodeQuota?,
        openCodeError: String?,
        location: QuotaPopupLocation,
    ) {
        if (project.isDisposed) {
            return
        }

        QuotaUsageService.getInstance().refreshNowAsync()
        var popup: JBPopup? = null
        val content = RefreshablePopupPanel<QuotaPopupContentState> { state ->
            buildPopupContent(project, component, state.quota, state.error, state.openCodeQuota, state.openCodeError) { popup?.cancel() }
        }.apply {
            refresh(QuotaPopupContentState(quota, error, openCodeQuota, openCodeError))
        }
        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, content)
            .setRequestFocus(true)
            .setFocusable(true)
            .setResizable(false)
            .setMovable(false)
            .createPopup()

        val currentPopup = popup
        val popupConnection: MessageBusConnection = ApplicationManager.getApplication().messageBus.connect(currentPopup)
        var latestQuota = quota
        var latestError = error
        var latestOpenCodeQuota = openCodeQuota
        var latestOpenCodeError = openCodeError
        var refreshScheduled = false
        fun scheduleRefresh() {
            if (refreshScheduled) {
                return
            }
            refreshScheduled = true
            ApplicationManager.getApplication().invokeLater {
                refreshScheduled = false
                refreshPopup(currentPopup, content, component, location, latestQuota, latestError, latestOpenCodeQuota, latestOpenCodeError)
            }
        }
        popupConnection.subscribe(QuotaUsageListener.TOPIC, object : QuotaUsageListener {
            override fun onQuotaUpdated(updatedQuota: OpenAiCodexQuota?, updatedError: String?) {
                latestQuota = updatedQuota
                latestError = updatedError
                scheduleRefresh()
            }

            override fun onOpenCodeQuotaUpdated(updatedQuota: OpenCodeQuota?, updatedError: String?) {
                latestOpenCodeQuota = updatedQuota
                latestOpenCodeError = updatedError
                scheduleRefresh()
            }
        })

        popup.show(RelativePoint(component, popupPoint(component, content, location)))
    }

    private fun refreshPopup(
        currentPopup: JBPopup,
        content: RefreshablePopupPanel<QuotaPopupContentState>,
        component: Component,
        location: QuotaPopupLocation,
        quota: OpenAiCodexQuota?,
        error: String?,
        openCodeQuota: OpenCodeQuota?,
        openCodeError: String?,
    ) {
        if (currentPopup.isDisposed || !currentPopup.isVisible) {
            return
        }
        val oldSize = content.preferredSize
        content.refresh(QuotaPopupContentState(quota, error, openCodeQuota, openCodeError))
        val newSize = content.preferredSize
        if (oldSize != newSize) {
            currentPopup.pack(true, true)
            val newPoint = popupPoint(component, content, location)
            val screenPoint = RelativePoint(component, newPoint).getScreenPoint()
            currentPopup.setLocation(screenPoint)
            currentPopup.moveToFitScreen()
        }
    }

    private fun popupPoint(component: Component, content: JComponent, location: QuotaPopupLocation): Point {
        val popupSize = content.preferredSize
        val x = (component.width - popupSize.width) / 2
        val gap = JBUI.scale(4)
        val y = when (location) {
            QuotaPopupLocation.ABOVE -> -popupSize.height - gap
            QuotaPopupLocation.BELOW -> component.height + gap
        }
        return Point(x, y)
    }

    private fun buildPopupContent(
        project: Project,
        component: Component,
        currentQuota: OpenAiCodexQuota?,
        currentError: String?,
        openCodeQuota: OpenCodeQuota?,
        openCodeError: String?,
        onClosePopup: () -> Unit,
    ): JComponent {
        val settings = QuotaSettingsState.getInstance()
        val hideOpenAi = settings.hideOpenAiFromQuotaPopup
        val hideOpenCode = settings.hideOpenCodeFromQuotaPopup
        val hasReviewData = currentQuota != null && (
            currentQuota.reviewPrimary != null || currentQuota.reviewSecondary != null ||
                currentQuota.reviewAllowed != null || currentQuota.reviewLimitReached != null
            )

        return createPopupStack().apply {
            add(createHeaderRow { openSettings(project, component) { onClosePopup() } })

            add(createSeparatedBlock())

            val authService = QuotaAuthService.getInstance()
            val openCodeCookieStore = OpenCodeSessionCookieStore.getInstance()
            val hasCodexAuth = authService.isLoggedIn()
            val hasOpenCodeAuth = openCodeCookieStore.load() != null
            val showCodexSection = hasCodexAuth && !hideOpenAi
            val showOpenCodeSection = hasOpenCodeAuth && !hideOpenCode

            if (!hasCodexAuth && !hasOpenCodeAuth) {
                add(withVerticalInsets(JBLabel("Not logged in."), top = 1))
                add(withVerticalInsets(ActionLink("Open Settings") { openSettings(project, component) { onClosePopup() } }, top = 3))
            } else if (!showCodexSection && !showOpenCodeSection) {
                add(withVerticalInsets(JBLabel("All quota sources are hidden from this popup."), top = 1))
                add(withVerticalInsets(ActionLink("Open Settings") { openSettings(project, component) { onClosePopup() } }, top = 3))
            } else {
                // Codex section
                if (showCodexSection) {
                    when {
                        currentError != null -> {
                            add(withVerticalInsets(createWarningLabel("Codex error: $currentError"), top = 1))
                        }

                        currentQuota == null -> {
                            add(withVerticalInsets(JBLabel("Loading Codex usage..."), top = 1))
                        }

                        else -> {
                            val limitWarning = getLimitWarning(currentQuota)
                            if (limitWarning != null) {
                                add(withVerticalInsets(createWarningLabel(limitWarning), top = 1))
                                add(createSeparatedBlock())
                            }

                            if (currentQuota.primary != null || currentQuota.secondary != null) {
                                add(withVerticalInsets(createSectionTitleLabel("Codex", QuotaIcons.OPENAI), top = 0))
                                val planLabel = currentQuota.planType?.toDisplayLabel()
                                if (!planLabel.isNullOrBlank()) {
                                    add(withVerticalInsets(createMutedLabel("OpenAI Plan: $planLabel"), top = 2))
                                }
                                currentQuota.primary?.let { add(createWindowBlock(it, "Primary", top = 3)) }
                                currentQuota.secondary?.let { add(createWindowBlock(it, "Secondary", top = 5)) }
                            }

                            if (hasReviewData) {
                                add(createSeparatedBlock())
                                add(withVerticalInsets(createSectionTitleLabel("Code Review", QuotaIcons.OPENAI), top = 0))
                                currentQuota.reviewPrimary?.let { add(createWindowBlock(it, "Primary", top = 3)) }
                                currentQuota.reviewSecondary?.let { add(createWindowBlock(it, "Secondary", top = 5)) }
                            }
                        }
                    }
                }

                // OpenCode Go section
                if (showOpenCodeSection) {
                    add(createSeparatedBlock())
                    when {
                        openCodeError != null -> {
                            add(withVerticalInsets(createWarningLabel("OpenCode error: $openCodeError"), top = 1))
                        }

                        openCodeQuota == null -> {
                            add(withVerticalInsets(JBLabel("Loading OpenCode usage..."), top = 1))
                        }

                        else -> {
                            add(withVerticalInsets(createSectionTitleLabel(OPENCODE_GO_LABEL, QuotaIcons.OPENCODE), top = 0))
                            val balanceText = openCodeQuota.availableBalance?.let(QuotaUiUtil::formatOpenCodeBalance)
                            if (balanceText != null) {
                                add(withVerticalInsets(createMutedLabel("Available balance: $$balanceText"), top = 2))
                            }
                            openCodeQuota.rollingUsage?.let {
                                add(createOpenCodeWindowBlock(it, "5h rolling", top = 3))
                            }
                            openCodeQuota.weeklyUsage?.let {
                                add(createOpenCodeWindowBlock(it, "Weekly", top = 5))
                            }
                            openCodeQuota.monthlyUsage?.let {
                                add(createOpenCodeWindowBlock(it, "Monthly", top = 5))
                            }
                        }
                    }
                }

                val fetchedAt = if (showCodexSection) QuotaUiUtil.formatInstant(currentQuota?.fetchedAt) else null
                if (fetchedAt != null) {
                    add(createSeparatedBlock())
                    add(withVerticalInsets(createMutedLabel("Last updated: $fetchedAt"), top = 1))
                }
            }
        }
    }

    private fun createOpenSettingsButton(onOpenSettings: () -> Unit): ActionLink {
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

    private fun createPopupStack(): NonOpaquePanel {
        return NonOpaquePanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 0, true, false)).apply {
            border = JBUI.Borders.empty(6, 8, 5, 8)
        }
    }

    private fun createHeaderRow(onOpenSettings: () -> Unit): JComponent {
        return BorderLayoutPanel().apply {
            isOpaque = false
            addToLeft(createPopupTitleLabel())
            addToRight(createOpenSettingsButton(onOpenSettings))
        }
    }

    private fun createWindowBlock(window: UsageWindow, fallbackLabel: String, top: Int): JComponent {
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

    private fun createOpenCodeWindowBlock(window: OpenCodeUsageWindow, label: String, top: Int): JComponent {
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

    private fun createSeparatedBlock(): JComponent {
        return withVerticalInsets(createCompactSeparator(), top = 5, bottom = 5)
    }

    private fun withVerticalInsets(component: JComponent, top: Int = 0, bottom: Int = 0): JComponent {
        return BorderLayoutPanel().apply {
            isOpaque = false
            border = JBUI.Borders.empty(top, 0, bottom, 0)
            addToCenter(component)
        }
    }

    private fun openSettings(project: Project, component: Component, beforeOpen: () -> Unit = {}) {
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

    private fun describeWindowLabel(window: UsageWindow, fallbackLabel: String): String {
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

    private fun getLimitWarning(quota: OpenAiCodexQuota?): String? {
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

    private fun String.toDisplayLabel(): String {
        return split(Regex(WHITESPACE_REGEX)).joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { character ->
                if (character.isLowerCase()) character.titlecase() else character.toString()
            }
        }
    }

    private fun createPopupTitleLabel(): JBLabel {
        return JBLabel("Subscription Quota").apply {
            font = font.deriveFont(font.style or Font.BOLD, font.size + 2f)
        }
    }

    private fun createWindowTitleLabel(text: String): JBLabel {
        return JBLabel(text).apply {
            font = font.deriveFont(font.style or Font.BOLD)
        }
    }

    private fun createSectionTitleLabel(text: String, icon: Icon? = null): JBLabel {
        return JBLabel(text).apply {
            if (icon != null) {
                this.icon = scaleIconToQuotaStatusSize(icon, this)
                iconTextGap = JBUI.scale(4)
            }
            foreground = JBColor.BLUE
            font = font.deriveFont(font.style or Font.BOLD, font.size + 1f)
        }
    }

    private fun createWarningLabel(text: String): JBLabel {
        return JBLabel(text).apply {
            foreground = JBColor.RED
            font = font.deriveFont(font.style or Font.BOLD)
        }
    }

    private fun createMutedLabel(text: String): JBLabel {
        return JBLabel(text).apply {
            foreground = JBColor.GRAY
        }
    }

    private fun createUsageProgressBar(percent: Int): JProgressBar {
        return JProgressBar(0, 100).apply {
            value = percent
            isStringPainted = false
            preferredSize = Dimension(200, 4)
        }
    }

    private fun createCompactSeparator(): JComponent {
        val separatorColor = JBUI.CurrentTheme.Popup.separatorColor()
        return SeparatorComponent(1, 0, separatorColor, separatorColor)
    }

    @Language("RegExp")
    private const val WHITESPACE_REGEX = "\\s+"
}

internal data class QuotaPopupContentState(
    val quota: OpenAiCodexQuota?,
    val error: String?,
    val openCodeQuota: OpenCodeQuota? = null,
    val openCodeError: String? = null,
)

internal class RefreshablePopupPanel<T>(private val renderer: (T) -> JComponent) : BorderLayoutPanel() {
    private var stableWidth = 0
    private var currentState: T? = null

    init {
        isOpaque = false
    }

    fun refresh(state: T) {
        if (state == currentState) {
            return
        }
        currentState = state
        removeAll()
        addToCenter(renderer(state))
        revalidate()
        repaint()
    }

    override fun getPreferredSize(): Dimension {
        val size = super.getPreferredSize()
        stableWidth = maxOf(stableWidth, size.width, JBUI.scale(260))
        return Dimension(stableWidth, size.height)
    }
}
