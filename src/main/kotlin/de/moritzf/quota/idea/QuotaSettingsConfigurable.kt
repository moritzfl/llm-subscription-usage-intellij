package de.moritzf.quota.idea

import com.intellij.icons.AllIcons
import com.intellij.ide.ActivityTracker
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.*
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.quota.OpenAiCodexQuota
import de.moritzf.quota.OpenCodeQuota
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.*

/**
 * Settings UI that manages authentication actions and shows latest quota payload data.
 */
class QuotaSettingsConfigurable : Configurable {
    private var rootComponent: JComponent? = null
    private var panel: DialogPanel? = null
    private var accountIdField: JBTextField? = null
    private var emailField: JBTextField? = null
    private var openCodeCookieField: JBPasswordField? = null
    private var openCodeStatusLabel: JBLabel? = null
    private var codexResponseViewer: JBTextArea? = null
    private var openCodeJsonViewer: JBTextArea? = null
    private var loginHeaderLabel: JBLabel? = null
    private var statusLabel: JBLabel? = null
    private var statusLabelDefaultForeground: Color? = null
    private var authStatusMessage: AuthStatusMessage? = null
    private var locationComboBox: ComboBox<QuotaIndicatorLocation>? = null
    private var displayModeComboBox: ComboBox<QuotaDisplayMode>? = null
    private var indicatorSourceComboBox: ComboBox<QuotaIndicatorSource>? = null
    private var openAiHideFromPopupCheckBox: JBCheckBox? = null
    private var openCodeHideFromPopupCheckBox: JBCheckBox? = null
    private var displayModePreview: DisplayModePreviewComponent? = null
    private var loginButton: ActionLink? = null
    private var cancelLoginButton: ActionLink? = null
    private var logoutButton: ActionLink? = null
    private var copyUrlButton: JButton? = null
    private var authUrl: String? = null
    private var connection: MessageBusConnection? = null
    private var updatingDisplayModeChoices: Boolean = false

    override fun getDisplayName(): String = "LLM Subscription Usage"

    override fun createComponent(): JComponent? {
        accountIdField = JBTextField().apply { isEditable = false }
        emailField = JBTextField().apply { isEditable = false }
        openCodeCookieField = JBPasswordField().apply {
            columns = 40
            toolTipText = "Session cookie from opencode.ai (extract from browser DevTools)"
        }
        openCodeStatusLabel = JBLabel().apply { isVisible = false }
        codexResponseViewer = createResponseViewer()
        openCodeJsonViewer = createResponseViewer()
        loginHeaderLabel = JBLabel()
        statusLabel = JBLabel().apply { isVisible = false }
        statusLabelDefaultForeground = statusLabel!!.foreground ?: UIManager.getColor("Label.foreground")
        locationComboBox = createIndicatorComboBox(QuotaIndicatorLocation.entries.toTypedArray())
        displayModeComboBox = createIndicatorComboBox(QuotaDisplayMode.entries.toTypedArray())
        indicatorSourceComboBox = createIndicatorComboBox(QuotaIndicatorSource.entries.toTypedArray())
        openAiHideFromPopupCheckBox = JBCheckBox("Hide from quota popup")
        openCodeHideFromPopupCheckBox = JBCheckBox("Hide from quota popup")
        displayModePreview = DisplayModePreviewComponent()
        loginButton = createActionLink("Log In")
        cancelLoginButton = createActionLink("Cancel Login")
        logoutButton = createActionLink("Log Out")
        copyUrlButton = JButton("Copy URL", AllIcons.Actions.Copy).apply {
            isVisible = false
            toolTipText = "Copy login URL to clipboard"
            addActionListener {
                val url = authUrl
                if (!url.isNullOrBlank()) {
                    val selection = StringSelection(url)
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
                }
            }
        }

        locationComboBox!!.addActionListener {
            updateDisplayModeChoices()
            updateDisplayModePreview()
        }

        displayModeComboBox!!.addActionListener {
            if (updatingDisplayModeChoices) {
                return@addActionListener
            }
            updateDisplayModePreview()
        }

        loginButton!!.addActionListener {
            val authService = QuotaAuthService.getInstance()
            if (authService.isLoggedIn()) {
                updateAuthUi()
                return@addActionListener
            }

            loginButton!!.isEnabled = false
            setStatusMessage("Opening browser...", kind = AuthStatusKind.PENDING)
            authService.startLoginFlow(callback = { result ->
                val currentPanel = panel ?: return@startLoginFlow
                ApplicationManager.getApplication().invokeLater({
                    if (panel == null || statusLabel == null || loginButton == null || logoutButton == null) {
                        return@invokeLater
                    }

                    if (result.success) {
                        setStatusMessage("Logged in")
                        QuotaUsageService.getInstance().refreshNowAsync()
                    } else {
                        val message = result.message ?: "Login failed"
                        val benignFailure = message == "Login canceled" || message == "Logged out"
                        setStatusMessage(if (benignFailure) message else "Login failed", isError = !benignFailure)
                        if (!benignFailure) {
                            Messages.showErrorDialog(currentPanel, message, "OpenAI Login")
                        }
                    }
                    loginButton!!.isEnabled = true
                    updateAuthUi()
                    updateAccountFields()
                }, ModalityState.stateForComponent(currentPanel))
            }, onAuthUrl = { url ->
                val currentPanel = panel ?: return@startLoginFlow
                ApplicationManager.getApplication().invokeLater({
                    if (copyUrlButton == null) return@invokeLater
                    authUrl = url
                    copyUrlButton!!.isVisible = true
                }, ModalityState.stateForComponent(currentPanel))
            })
            updateAuthUi()
        }

        cancelLoginButton!!.addActionListener {
            val aborted = QuotaAuthService.getInstance().abortLogin("Login canceled")
            setStatusMessage(if (aborted) "Login canceled" else "No login in progress")
            updateAuthUi()
        }

        logoutButton!!.addActionListener {
            QuotaAuthService.getInstance().clearCredentials()
            QuotaUsageService.getInstance().clearUsageData("Not logged in")
            setStatusMessage("Logged out")
            updateAuthUi()
            updateAccountFields()
        }

        // Indicator config panel (has onApply / onReset / onIsModified)
        panel = panel {
            row("Indicator location:") {
                cell(locationComboBox!!)
            }

            row("Indicator display:") {
                cell(displayModeComboBox!!)
                cell(displayModePreview!!).gap(RightGap.SMALL)
            }

            row("Indicator quota source:") {
                cell(indicatorSourceComboBox!!)
            }

            onApply {
                val selectedLocation = locationComboBox?.selectedItem as? QuotaIndicatorLocation ?: return@onApply
                val selectedDisplayMode = displayModeComboBox?.selectedItem as? QuotaDisplayMode ?: return@onApply
                val selectedSource = indicatorSourceComboBox?.selectedItem as? QuotaIndicatorSource ?: return@onApply
                val sanitizedDisplayMode = QuotaDisplayMode.sanitizeFor(selectedLocation, selectedDisplayMode)
                val state = QuotaSettingsState.getInstance()
                val locationChanged = selectedLocation != state.location()
                val displayModeChanged = sanitizedDisplayMode != state.displayMode()
                val sourceChanged = selectedSource != state.source()
                val openAiPopupVisibilityChanged = openAiHideFromPopupCheckBox?.isSelected != state.hideOpenAiFromQuotaPopup
                val openCodePopupVisibilityChanged = openCodeHideFromPopupCheckBox?.isSelected != state.hideOpenCodeFromQuotaPopup
                if (locationChanged) {
                    state.setLocation(selectedLocation)
                }
                if (displayModeChanged) {
                    state.setDisplayMode(sanitizedDisplayMode)
                }
                if (sourceChanged) {
                    state.setSource(selectedSource)
                }
                state.hideOpenAiFromQuotaPopup = openAiHideFromPopupCheckBox?.isSelected == true
                state.hideOpenCodeFromQuotaPopup = openCodeHideFromPopupCheckBox?.isSelected == true
                if (locationChanged || displayModeChanged || sourceChanged || openAiPopupVisibilityChanged || openCodePopupVisibilityChanged) {
                    ApplicationManager.getApplication().messageBus
                        .syncPublisher(QuotaSettingsListener.TOPIC)
                        .onSettingsChanged()
                    ActivityTracker.getInstance().inc()
                }
            }

            onReset {
                authStatusMessage = null
                locationComboBox?.selectedItem = QuotaSettingsState.getInstance().location()
                updateDisplayModeChoices(QuotaSettingsState.getInstance().displayMode())
                updateDisplayModePreview()
                indicatorSourceComboBox?.selectedItem = QuotaSettingsState.getInstance().source()
                openAiHideFromPopupCheckBox?.isSelected = QuotaSettingsState.getInstance().hideOpenAiFromQuotaPopup
                openCodeHideFromPopupCheckBox?.isSelected = QuotaSettingsState.getInstance().hideOpenCodeFromQuotaPopup
                updateAuthUi()
                updateAccountFields()
                updateResponseArea()
                updateOpenCodeResponseArea()
                updateOpenCodeFields()
            }

            onIsModified {
                val selectedLocation = locationComboBox?.selectedItem as? QuotaIndicatorLocation ?: return@onIsModified false
                val selectedDisplayMode = displayModeComboBox?.selectedItem as? QuotaDisplayMode ?: return@onIsModified false
                val selectedSource = indicatorSourceComboBox?.selectedItem as? QuotaIndicatorSource ?: return@onIsModified false
                val state = QuotaSettingsState.getInstance()
                selectedLocation != state.location() ||
                    QuotaDisplayMode.sanitizeFor(selectedLocation, selectedDisplayMode) != state.displayMode() ||
                    selectedSource != state.source() ||
                    openAiHideFromPopupCheckBox?.isSelected != state.hideOpenAiFromQuotaPopup ||
                    openCodeHideFromPopupCheckBox?.isSelected != state.hideOpenCodeFromQuotaPopup
            }
        }.apply {
            preferredFocusedComponent = locationComboBox
        }

        // Codex tab content
        val codexConfigPanel = panel {
            row {
                cell(openAiHideFromPopupCheckBox!!)
            }
            row { cell(loginHeaderLabel!!) }
            row {
                cell(statusLabel!!).gap(RightGap.SMALL)
                cell(copyUrlButton!!)
            }
            row {
                cell(loginButton!!).gap(RightGap.SMALL)
                cell(cancelLoginButton!!).gap(RightGap.SMALL)
                cell(logoutButton!!)
            }
            separator()
            row("Account ID:") {
                cell(accountIdField!!)
                    .resizableColumn()
                    .align(AlignX.FILL)
            }
            row("Email:") {
                cell(emailField!!)
                    .resizableColumn()
                    .align(AlignX.FILL)
            }
        }

        val codexTab = BorderLayoutPanel().apply {
            addToTop(codexConfigPanel)
            addToCenter(
                BorderLayoutPanel().apply {
                    addToTop(
                        JBLabel("Last quota response:"),
                    )
                    addToCenter(createResponseViewerPanel(codexResponseViewer!!))
                },
            )
        }

        // OpenCode tab content
        val openCodeConfigPanel = panel {
            row {
                cell(openCodeHideFromPopupCheckBox!!)
            }
            row {
                label("Session cookie")
            }
            row {
                cell(openCodeStatusLabel!!)
            }
            row("Session cookie:") {
                cell(openCodeCookieField!!)
                    .resizableColumn()
                    .align(AlignX.FILL)
            }
            row {
                button("Save") {
                    val cookie = String(openCodeCookieField!!.password)
                    if (cookie.isNotBlank() && cookie != OPENCODE_COOKIE_PLACEHOLDER) {
                        OpenCodeSessionCookieStore.getInstance().save(cookie)
                        updateOpenCodeFields()
                        updateOpenCodeStatus()
                        QuotaUsageService.getInstance().refreshNowAsync()
                    }
                }
                button("Clear") {
                    OpenCodeSessionCookieStore.getInstance().clear()
                    openCodeCookieField!!.text = ""
                    updateOpenCodeStatus()
                    QuotaUsageService.getInstance().clearOpenCodeUsageData()
                }
            }
            separator()
            row {
                label("Extract from opencode.ai → DevTools → Storage → Cookies → \"auth\" cookie value. Valid for 1 year.")
            }
        }

        val openCodeTab = BorderLayoutPanel().apply {
            addToTop(openCodeConfigPanel)
            addToCenter(
                BorderLayoutPanel().apply {
                    addToTop(
                        JBLabel("Last quota response:"),
                    )
                    addToCenter(createResponseViewerPanel(openCodeJsonViewer!!))
                },
            )
        }

        // Service tabs
        val serviceTabs = JBTabbedPane().apply {
            addTab("OpenAI Codex", codexTab)
            addTab("OpenCode Go", openCodeTab)
        }

        // Root component
        rootComponent = BorderLayoutPanel().apply {
            isOpaque = false
            addToTop(panel!!)
            addToCenter(serviceTabs)
        }

        connection = ApplicationManager.getApplication().messageBus.connect()
        connection!!.subscribe(QuotaUsageListener.TOPIC, object : QuotaUsageListener {
            override fun onQuotaUpdated(quota: OpenAiCodexQuota?, error: String?) {
                val currentPanel = rootComponent ?: panel ?: return@onQuotaUpdated
                ApplicationManager.getApplication().invokeLater({
                    if ((rootComponent == null && panel == null) || codexResponseViewer == null || accountIdField == null || emailField == null) {
                        return@invokeLater
                    }
                    updateAccountFields()
                    updateResponseArea()
                    updateOpenCodeStatus()
                }, ModalityState.stateForComponent(currentPanel))
            }

            override fun onOpenCodeQuotaUpdated(quota: OpenCodeQuota?, error: String?) {
                val currentPanel = rootComponent ?: panel ?: return@onOpenCodeQuotaUpdated
                ApplicationManager.getApplication().invokeLater({
                    if (rootComponent == null && panel == null) {
                        return@invokeLater
                    }
                    updateOpenCodeResponseArea()
                    updateOpenCodeStatus()
                }, ModalityState.stateForComponent(currentPanel))
            }
        })

        reset()
        return rootComponent
    }

    override fun isModified(): Boolean {
        return panel?.isModified() == true
    }

    override fun apply() {
        panel?.apply()
    }

    override fun reset() {
        panel?.reset()
    }

    override fun disposeUIResources() {
        connection?.disconnect()
        connection = null
        rootComponent = null
        panel = null
        accountIdField = null
        emailField = null
        openCodeCookieField = null
        openCodeStatusLabel = null
        codexResponseViewer = null
        openCodeJsonViewer = null
        loginHeaderLabel = null
        statusLabel = null
        statusLabelDefaultForeground = null
        authStatusMessage = null
        locationComboBox = null
        displayModeComboBox = null
        indicatorSourceComboBox = null
        openAiHideFromPopupCheckBox = null
        openCodeHideFromPopupCheckBox = null
        displayModePreview = null
        loginButton = null
        cancelLoginButton = null
        logoutButton = null
        copyUrlButton = null
        authUrl = null
        updatingDisplayModeChoices = false
    }

    private fun updateDisplayModeChoices(preferredMode: QuotaDisplayMode? = null) {
        val combo = displayModeComboBox ?: return
        val location = locationComboBox?.selectedItem as? QuotaIndicatorLocation ?: return
        val selectedMode = preferredMode ?: combo.selectedItem as? QuotaDisplayMode ?: QuotaSettingsState.getInstance().displayMode()
        val sanitizedMode = QuotaDisplayMode.sanitizeFor(location, selectedMode)
        updatingDisplayModeChoices = true
        try {
            combo.removeAllItems()
            QuotaDisplayMode.supportedFor(location).forEach(combo::addItem)
            combo.selectedItem = sanitizedMode
        } finally {
            updatingDisplayModeChoices = false
        }
    }

    private fun updateAuthUi() {
        val authService = QuotaAuthService.getInstance()
        val loggedIn = authService.isLoggedIn()
        val inProgress = authService.isLoginInProgress()
        val uiState = QuotaSettingsAuthUiState.create(loggedIn, inProgress, authStatusMessage)
        loginHeaderLabel?.text = uiState.headerText
        loginButton?.isEnabled = uiState.loginEnabled
        cancelLoginButton?.isEnabled = uiState.cancelEnabled
        logoutButton?.isEnabled = uiState.logoutEnabled
        renderStatusMessage(uiState.visibleStatusMessage)
        if (!inProgress) {
            copyUrlButton?.isVisible = false
            authUrl = null
        }
    }

    private fun setStatusMessage(text: String, isError: Boolean = false, kind: AuthStatusKind? = null) {
        authStatusMessage = AuthStatusMessage(text, isError, kind ?: if (isError) AuthStatusKind.DISCONNECTED else AuthStatusKind.CONNECTED)
    }

    private fun renderStatusMessage(message: AuthStatusMessage?) {
        val label = statusLabel ?: return
        label.text = message?.let { formatStatusText(it.text, it.kind) }.orEmpty()
        label.foreground = statusLabelDefaultForeground ?: label.foreground
        label.isVisible = !message?.text.isNullOrBlank()
    }

    private fun updateResponseArea() {
        val area = codexResponseViewer ?: return
        val json = QuotaUsageService.getInstance().getLastResponseJson()
        area.text = if (json.isNullOrBlank()) "No quota response yet." else json
        area.setCaretPosition(0)
    }

    private fun updateOpenCodeResponseArea() {
        val jsonArea = openCodeJsonViewer ?: return
        val quota = QuotaUsageService.getInstance().getLastOpenCodeQuota()
        val error = QuotaUsageService.getInstance().getLastOpenCodeError()

        jsonArea.text = when {
            error != null -> "Error: $error"
            quota == null -> "No OpenCode response yet."
            else -> {
                try {
                    de.moritzf.quota.JsonSupport.json.encodeToString(
                        de.moritzf.quota.OpenCodeQuota.serializer(),
                        quota,
                    )
                } catch (exception: Exception) {
                    "Could not serialize response: ${exception.message}"
                }
            }
        }
        jsonArea.setCaretPosition(0)
    }

    private fun createResponseViewer(): JBTextArea {
        return JBTextArea().apply {
            isEditable = false
            lineWrap = false
            wrapStyleWord = false
            font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
            margin = JBUI.insets(6)
        }
    }

    private fun createResponseViewerPanel(viewer: JBTextArea): JComponent {
        return JBScrollPane(viewer).apply {
            preferredSize = Dimension(1, JBUI.scale(220))
            minimumSize = Dimension(1, JBUI.scale(120))
            border = JBUI.Borders.emptyTop(4)
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }
    }

    private fun updateAccountFields() {
        val accountField = accountIdField ?: return
        val emailField = emailField ?: return
        val authService = QuotaAuthService.getInstance()
        val accountId = authService.getAccountId()
        val email = if (authService.isLoggedIn()) QuotaUsageService.getInstance().getLastQuota()?.email else null
        accountField.text = accountId.orEmpty()
        emailField.text = email.orEmpty()
    }

    private fun updateOpenCodeFields() {
        val cookie = OpenCodeSessionCookieStore.getInstance().load()
        openCodeCookieField?.text = if (cookie.isNullOrBlank()) "" else OPENCODE_COOKIE_PLACEHOLDER
        updateOpenCodeStatus()
    }

    private fun updateOpenCodeStatus() {
        val label = openCodeStatusLabel ?: return
        val cookieStore = OpenCodeSessionCookieStore.getInstance()
        val cookie = cookieStore.load()
        val openCodeQuota = QuotaUsageService.getInstance().getLastOpenCodeQuota()
        val openCodeError = QuotaUsageService.getInstance().getLastOpenCodeError()

        when {
            cookie == null -> {
                label.text = formatStatusText("No session cookie configured", AuthStatusKind.DISCONNECTED)
                label.foreground = statusLabelDefaultForeground ?: label.foreground
            }
            openCodeError != null -> {
                label.text = formatStatusText("Error: $openCodeError", AuthStatusKind.DISCONNECTED)
                label.foreground = statusLabelDefaultForeground ?: label.foreground
            }
            openCodeQuota != null -> {
                val balanceText = if (openCodeQuota.useBalance) openCodeQuota.availableBalance?.let { "Balance: $${QuotaUiUtil.formatOpenCodeBalance(it)}" } else null
                val text = if (balanceText != null) "Connected - Go subscription active - $balanceText" else "Connected - Go subscription active"
                label.text = formatStatusText(text, AuthStatusKind.CONNECTED)
                label.foreground = statusLabelDefaultForeground ?: label.foreground
            }
            else -> {
                label.text = formatStatusText("Session cookie stored securely", AuthStatusKind.CONNECTED)
                label.foreground = statusLabelDefaultForeground ?: label.foreground
            }
        }
        label.isVisible = true
    }

    private fun formatStatusText(text: String, kind: AuthStatusKind): String {
        val color = when (kind) {
            AuthStatusKind.CONNECTED -> "#4CAF50"
            AuthStatusKind.DISCONNECTED -> "#F44336"
            AuthStatusKind.PENDING -> "#FFC107"
        }
        return "<html><span style='color:$color'>●</span>&nbsp;$text</html>"
    }

    private fun updateDisplayModePreview() {
        val mode = displayModeComboBox?.selectedItem as? QuotaDisplayMode ?: return
        displayModePreview?.updateMode(mode)
    }

    private fun createActionLink(text: String): ActionLink {
        return ActionLink(text).apply {
            autoHideOnDisable = false
        }
    }

    private fun <T> createIndicatorComboBox(items: Array<T>): ComboBox<T> {
        return ComboBox(items).apply {
            preferredSize = Dimension(JBUI.scale(220), preferredSize.height)
            minimumSize = preferredSize
        }
    }

    private class DisplayModePreviewComponent : BorderLayoutPanel() {
        private val previewIconLabel = JBLabel().apply {
            horizontalAlignment = SwingConstants.CENTER
            verticalAlignment = SwingConstants.CENTER
        }
        private val percentagePreview = QuotaPercentageIndicator().apply {
            update("42% • 2h 9m", 0.42, QuotaUsageColors.GREEN)
        }

        init {
            isOpaque = false
            updateMode(QuotaDisplayMode.ICON_ONLY)
        }

        fun updateMode(mode: QuotaDisplayMode) {
            removeAll()
            when (mode) {
                QuotaDisplayMode.ICON_ONLY -> {
                    previewIconLabel.icon = QuotaIcons.STATUS
                    addToCenter(previewIconLabel)
                }

                QuotaDisplayMode.CAKE_DIAGRAM -> {
                    previewIconLabel.icon = scaledCakeIcon(previewIconLabel)
                    addToCenter(previewIconLabel)
                }

                QuotaDisplayMode.PERCENTAGE_BAR -> {
                    addToCenter(percentagePreview)
                }
            }
            revalidate()
            repaint()
        }

        private fun scaledCakeIcon(component: JComponent): Icon {
            return scaleIconToQuotaStatusSize(QuotaIcons.CAKE_40, component)
        }
    }

    private companion object {
        private const val OPENCODE_COOKIE_PLACEHOLDER = "********"
    }
}

internal data class AuthStatusMessage(
    val text: String,
    val isError: Boolean = false,
    val kind: AuthStatusKind = if (isError) AuthStatusKind.DISCONNECTED else AuthStatusKind.CONNECTED,
)

internal enum class AuthStatusKind {
    CONNECTED,
    DISCONNECTED,
    PENDING,
}

internal data class QuotaSettingsAuthUiState(
    val headerText: String,
    val visibleStatusMessage: AuthStatusMessage?,
    val loginEnabled: Boolean,
    val cancelEnabled: Boolean,
    val logoutEnabled: Boolean,
) {
    companion object {
        fun create(loggedIn: Boolean, inProgress: Boolean, statusMessage: AuthStatusMessage?): QuotaSettingsAuthUiState {
            val visibleStatusMessage = statusMessage ?: if (inProgress) {
                AuthStatusMessage("Complete the login in your browser.", kind = AuthStatusKind.PENDING)
            } else if (loggedIn) {
                AuthStatusMessage("Connected", isError = false)
            } else {
                AuthStatusMessage("Not logged in", isError = true)
            }
            return QuotaSettingsAuthUiState(
                headerText = "Login",
                visibleStatusMessage = visibleStatusMessage,
                loginEnabled = !inProgress && !loggedIn,
                cancelEnabled = inProgress,
                logoutEnabled = loggedIn,
            )
        }
    }
}
