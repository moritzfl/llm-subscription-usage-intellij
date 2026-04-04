package de.moritzf.quota.idea

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.JBColor
import com.intellij.ui.SeparatorComponent
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.NonOpaquePanel
import com.intellij.util.IconUtil
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.quota.OpenAiCodexQuota
import de.moritzf.quota.UsageWindow
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import kotlin.math.roundToInt

/**
 * Status bar widget that displays the current quota state and shows a detailed popup.
 */
class QuotaStatusBarWidget(private val project: Project) : CustomStatusBarWidget {
    private val connection: MessageBusConnection
    private val widgetComponent = UsageWidgetComponent()
    @Volatile
    private var quota: OpenAiCodexQuota?
    @Volatile
    private var error: String?
    private var statusBar: StatusBar? = null

    init {
        val usageService = QuotaUsageService.getInstance()
        quota = usageService.getLastQuota()
        error = usageService.getLastError()
        connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(QuotaUsageListener.TOPIC, QuotaUsageListener { updatedQuota, updatedError ->
            quota = updatedQuota
            error = updatedError
            updateWidget()
        })
        connection.subscribe(QuotaSettingsListener.TOPIC, QuotaSettingsListener { updateWidget() })
        widgetComponent.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                showPopup(widgetComponent)
            }
        })
    }

    override fun ID(): String = QuotaStatusBarWidgetFactory.ID

    override fun getComponent(): JComponent = widgetComponent

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        updateWidget()
    }

    override fun dispose() {
        connection.dispose()
    }

    private fun updateWidget() {
        widgetComponent.updateUsage()
        statusBar?.updateWidget(ID())
    }

    private fun showPopup(component: Component?) {
        if (component == null) {
            return
        }

        QuotaUsageService.getInstance().refreshNowAsync()
        var popup: JBPopup? = null
        val content = UsagePopupContent { openSettings { popup?.cancel() } }
        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, content)
            .setRequestFocus(true)
            .setFocusable(true)
            .setResizable(false)
            .setMovable(false)
            .createPopup()
        Disposer.register(this, popup)

        val currentPopup = popup
        val popupConnection = ApplicationManager.getApplication().messageBus.connect(currentPopup)
        popupConnection.subscribe(QuotaUsageListener.TOPIC, QuotaUsageListener { updatedQuota, updatedError ->
            ApplicationManager.getApplication().invokeLater {
                if (currentPopup.isDisposed || !currentPopup.isVisible) {
                    return@invokeLater
                }
                content.refresh(updatedQuota, updatedError)
                currentPopup.pack(true, true)
            }
        })

        val popupSize = content.preferredSize
        val x = (component.width - popupSize.width) / 2
        val y = -popupSize.height - JBUI.scale(4)
        popup.show(RelativePoint(component, Point(x, y)))
    }

    private fun buildTooltipText(): String {
        val authService = QuotaAuthService.getInstance()
        if (!authService.isLoggedIn()) {
            return "OpenAI usage quota: not logged in"
        }
        if (error != null) {
            return "OpenAI usage quota: $error"
        }
        val primary = quota?.primary ?: return "OpenAI usage quota: loading"
        return "OpenAI usage quota: ${clampPercent(primary.usedPercent.roundToInt())}% used"
    }

    private fun buildPopupContent(
        currentQuota: OpenAiCodexQuota?,
        currentError: String?,
        onOpenSettings: () -> Unit,
    ): JComponent {
        val hasReviewData = currentQuota != null && (
                currentQuota.reviewPrimary != null || currentQuota.reviewSecondary != null ||
                        currentQuota.reviewAllowed != null || currentQuota.reviewLimitReached != null
                )

        val content = createPopupStack().apply {
            add(createHeaderRow(onOpenSettings))

            val planLabel = currentQuota?.planType?.toDisplayLabel()
            if (!planLabel.isNullOrBlank()) {
                add(withVerticalInsets(createMutedLabel("Plan: $planLabel"), top = 3))
            }

            add(createSeparatedBlock())

            val authService = QuotaAuthService.getInstance()
            when {
                !authService.isLoggedIn() -> {
                    add(withVerticalInsets(JBLabel("Not logged in."), top = 1))
                    add(withVerticalInsets(ActionLink("Open Settings") { onOpenSettings() }, top = 3))
                }

                currentError != null -> {
                    add(withVerticalInsets(createWarningLabel("Error: $currentError"), top = 1))
                    add(withVerticalInsets(ActionLink("Open Settings") { onOpenSettings() }, top = 3))
                }

                currentQuota == null -> {
                    add(withVerticalInsets(JBLabel("Loading usage data..."), top = 1))
                }

                else -> {
                    val limitWarning = getLimitWarning(currentQuota)
                    if (limitWarning != null) {
                        add(withVerticalInsets(createWarningLabel(limitWarning), top = 1))
                        add(createSeparatedBlock())
                    }

                    if (currentQuota.primary != null || currentQuota.secondary != null) {
                        add(withVerticalInsets(createSectionTitleLabel("Codex"), top = 0))
                        currentQuota.primary?.let { add(createWindowBlock(it, "Primary", top = 3)) }
                        currentQuota.secondary?.let { add(createWindowBlock(it, "Secondary", top = 5)) }
                    }

                    if (hasReviewData) {
                        add(createSeparatedBlock())
                        add(withVerticalInsets(createSectionTitleLabel("Code Review"), top = 0))
                        currentQuota.reviewPrimary?.let { add(createWindowBlock(it, "Primary", top = 3)) }
                        currentQuota.reviewSecondary?.let { add(createWindowBlock(it, "Secondary", top = 5)) }
                    }

                    val fetchedAt = QuotaUiUtil.formatInstant(currentQuota.fetchedAt)
                    if (fetchedAt != null) {
                        add(createSeparatedBlock())
                        add(withVerticalInsets(createMutedLabel("Last updated: $fetchedAt"), top = 1))
                    }
                }
            }
        }

        return content
    }

    private inner class UsagePopupContent(private val onOpenSettings: () -> Unit) : BorderLayoutPanel() {
        init {
            isOpaque = false
            refresh(quota, error)
        }

        fun refresh(updatedQuota: OpenAiCodexQuota?, updatedError: String?) {
            removeAll()
            addToCenter(buildPopupContent(updatedQuota, updatedError, onOpenSettings))
            revalidate()
            repaint()
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
            addToLeft(createPopupTitleLabel("OpenAI usage"))
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

    private fun openSettings(beforeOpen: () -> Unit = {}) {
        if (project.isDisposed) {
            return
        }

        val modality = statusBar?.component?.let { ModalityState.stateForComponent(it) }
            ?: ModalityState.defaultModalityState()
        ApplicationManager.getApplication().invokeLater(
            {
                beforeOpen()
                ShowSettingsUtil.getInstance().showSettingsDialog(project, QuotaSettingsConfigurable::class.java)
            },
            modality,
        )
    }

    private inner class UsageWidgetComponent : BorderLayoutPanel() {
        private val statusIconLabel = createStatusIconLabel()
        private val percentageComponent = QuotaPercentageIndicator()

        init {
            isOpaque = false
            border = JBUI.Borders.empty(0, 4)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = " "
            updateUsage()
        }

        fun updateUsage() {
            when (displayMode()) {
                QuotaDisplayMode.ICON_ONLY -> {
                    statusIconLabel.icon = QuotaIcons.STATUS
                    showContent(statusIconLabel)
                }

                QuotaDisplayMode.CAKE_DIAGRAM -> {
                    statusIconLabel.icon = scaledCakeIcon(statusIconLabel)
                    showContent(statusIconLabel)
                }

                QuotaDisplayMode.PERCENTAGE_BAR -> {
                    updatePercentageDisplay()
                    showContent(percentageComponent)
                }
            }
            revalidate()
            repaint()
        }

        override fun getToolTipText(event: MouseEvent?): String = buildTooltipText()

        private fun showContent(component: JComponent) {
            removeAll()
            addToCenter(component)
        }

        private fun createStatusIconLabel(): JBLabel {
            return JBLabel().apply {
                horizontalAlignment = SwingConstants.CENTER
                verticalAlignment = SwingConstants.CENTER
            }
        }

        private fun barDisplayText(): String {
            val authService = QuotaAuthService.getInstance()
            if (!authService.isLoggedIn()) {
                return "OpenAI: not logged in"
            }
            if (error != null) {
                return "OpenAI: error"
            }

            val primary = quota?.primary ?: return "OpenAI: loading..."
            val percent = clampPercent(primary.usedPercent.roundToInt())
            val reset = QuotaUiUtil.formatResetCompact(primary.resetsAt)
            return if (reset != null) "$percent% • $reset" else "$percent%"
        }

        private fun cakeIcon(): Icon {
            val authService = QuotaAuthService.getInstance()
            if (!authService.isLoggedIn() || error != null) {
                return QuotaIcons.CAKE_UNKNOWN
            }

            val primary = quota?.primary ?: return QuotaIcons.CAKE_UNKNOWN
            if (quota?.limitReached == true) {
                return QuotaIcons.CAKE_100
            }

            val percent = clampPercent(primary.usedPercent.roundToInt())
            if (percent >= 100) {
                return QuotaIcons.CAKE_100
            }
            if (percent <= 0) {
                return QuotaIcons.CAKE_0
            }

            val bucket = minOf(95, ((percent + 4) / 5) * 5)
            return when (bucket) {
                5 -> QuotaIcons.CAKE_5
                10 -> QuotaIcons.CAKE_10
                15 -> QuotaIcons.CAKE_15
                20 -> QuotaIcons.CAKE_20
                25 -> QuotaIcons.CAKE_25
                30 -> QuotaIcons.CAKE_30
                35 -> QuotaIcons.CAKE_35
                40 -> QuotaIcons.CAKE_40
                45 -> QuotaIcons.CAKE_45
                50 -> QuotaIcons.CAKE_50
                55 -> QuotaIcons.CAKE_55
                60 -> QuotaIcons.CAKE_60
                65 -> QuotaIcons.CAKE_65
                70 -> QuotaIcons.CAKE_70
                75 -> QuotaIcons.CAKE_75
                80 -> QuotaIcons.CAKE_80
                85 -> QuotaIcons.CAKE_85
                90 -> QuotaIcons.CAKE_90
                95 -> QuotaIcons.CAKE_95
                else -> QuotaIcons.CAKE_UNKNOWN
            }
        }

        private fun scaledCakeIcon(component: JComponent): Icon {
            val cakeIcon = cakeIcon()
            val statusIcon = QuotaIcons.STATUS
            val targetWidth = statusIcon.iconWidth
            val targetHeight = statusIcon.iconHeight
            val iconWidth = cakeIcon.iconWidth
            val iconHeight = cakeIcon.iconHeight
            if (iconWidth <= 0 || iconHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
                return cakeIcon
            }
            if (iconWidth <= targetWidth && iconHeight <= targetHeight) {
                return cakeIcon
            }

            val widthScale = targetWidth / iconWidth.toFloat()
            val heightScale = targetHeight / iconHeight.toFloat()
            return IconUtil.scale(cakeIcon, component, minOf(widthScale, heightScale))
        }

        private fun displayMode(): QuotaDisplayMode = QuotaSettingsState.getInstance().displayMode()

        private fun displayPercent(): Int {
            val authService = QuotaAuthService.getInstance()
            if (!authService.isLoggedIn() || error != null) {
                return -1
            }
            val primary = quota?.primary ?: return -1
            return clampPercent(primary.usedPercent.roundToInt())
        }

        private fun updatePercentageDisplay() {
            val percentage = displayPercent()
            if (percentage >= 0) {
                percentageComponent.update(
                    text = barDisplayText(),
                    fraction = percentage / 100.0,
                    fillColor = QuotaUsageColors.usageColor(percentage),
                )
            } else {
                percentageComponent.update(
                    text = barDisplayText(),
                    fraction = 0.0,
                    fillColor = QuotaUsageColors.GRAY,
                )
            }
        }
    }

    companion object {
        private fun describeWindowLabel(window: UsageWindow, fallbackLabel: String): String {
            val minutes = window.windowDuration?.inWholeMinutes ?: return "$fallbackLabel limit"
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

        private fun clampPercent(value: Int): Int = value.coerceIn(0, 100)

        private fun String.toDisplayLabel(): String {
            return split(Regex("\\s+")).joinToString(" ") { word ->
                word.lowercase().replaceFirstChar { character ->
                    if (character.isLowerCase()) character.titlecase() else character.toString()
                }
            }
        }
    }

    private fun createPopupTitleLabel(text: String): JBLabel {
        return JBLabel(text).apply {
            font = font.deriveFont(font.style or java.awt.Font.BOLD, font.size + 2f)
        }
    }

    private fun createWindowTitleLabel(text: String): JBLabel {
        return JBLabel(text).apply {
            font = font.deriveFont(font.style or java.awt.Font.BOLD)
        }
    }

    private fun createSectionTitleLabel(text: String): JBLabel {
        return JBLabel(text).apply {
            foreground = JBColor.BLUE
            font = font.deriveFont(font.style or java.awt.Font.BOLD, font.size + 1f)
        }
    }

    private fun createWarningLabel(text: String): JBLabel {
        return JBLabel(text).apply {
            foreground = JBColor.RED
            font = font.deriveFont(font.style or java.awt.Font.BOLD)
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
}
