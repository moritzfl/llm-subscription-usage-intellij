package de.moritzf.quota.idea

import com.intellij.icons.AllIcons
import com.intellij.ide.ActivityTracker
import com.intellij.lang.Language
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.LanguageTextField
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
    private var codexResponseViewer: EditorTextField? = null
    private var openCodeRawViewer: EditorTextField? = null
    private var openCodeJsonViewer: EditorTextField? = null
    private var openCodeResponseTabbedPane: JBTabbedPane? = null
    private var loginHeaderLabel: JBLabel? = null
    private var statusLabel: JBLabel? = null
    private var statusLabelDefaultForeground: Color? = null
    private var authStatusMessage: AuthStatusMessage? = null
    private var locationComboBox: ComboBox<QuotaIndicatorLocation>? = null
    private var displayModeComboBox: ComboBox<QuotaDisplayMode>? = null
    private var displayModePreview: DisplayModePreviewComponent? = null
    private var loginButton: ActionLink? = null
    private var cancelLoginButton: ActionLink? = null
    private var logoutButton: ActionLink? = null
    private var copyUrlButton: JButton? = null
    private var authUrl: String? = null
    private var connection: MessageBusConnection? = null
    private var updatingDisplayModeChoices: Boolean = false

    override fun getDisplayName(): String = "OpenAI Usage Quota"

    override fun createComponent(): JComponent? {
        accountIdField = JBTextField().apply { isEditable = false }
        emailField = JBTextField().apply { isEditable = false }
        openCodeCookieField = JBPasswordField().apply {
            columns = 40
            toolTipText = "Session cookie from opencode.ai (extract from browser DevTools)"
        }
        openCodeStatusLabel = JBLabel().apply { isVisible = false }
        codexResponseViewer = createResponseViewer()
        openCodeRawViewer = createResponseViewer()
        openCodeJsonViewer = createResponseViewer()
        openCodeResponseTabbedPane = JBTabbedPane().apply {
            addTab("Raw", openCodeRawViewer)
            addTab("JSON", openCodeJsonViewer)
        }
        loginHeaderLabel = JBLabel()
        statusLabel = JBLabel().apply { isVisible = false }
        statusLabelDefaultForeground = statusLabel!!.foreground ?: UIManager.getColor("Label.foreground")
        locationComboBox = ComboBox(QuotaIndicatorLocation.entries.toTypedArray())
        displayModeComboBox = ComboBox(QuotaDisplayMode.entries.toTypedArray())
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
            setStatusMessage("Opening browser...")
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

            onApply {
                val selectedLocation = locationComboBox?.selectedItem as? QuotaIndicatorLocation ?: return@onApply
                val selectedDisplayMode = displayModeComboBox?.selectedItem as? QuotaDisplayMode ?: return@onApply
                val sanitizedDisplayMode = QuotaDisplayMode.sanitizeFor(selectedLocation, selectedDisplayMode)
                val state = QuotaSettingsState.getInstance()
                val locationChanged = selectedLocation != state.location()
                val displayModeChanged = sanitizedDisplayMode != state.displayMode()
                if (locationChanged) {
                    state.setLocation(selectedLocation)
                }
                if (displayModeChanged) {
                    state.setDisplayMode(sanitizedDisplayMode)
                }
                if (locationChanged || displayModeChanged) {
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
                updateAuthUi()
                updateAccountFields()
                updateResponseArea()
                updateOpenCodeResponseArea()
                updateOpenCodeFields()
            }

            onIsModified {
                val selectedLocation = locationComboBox?.selectedItem as? QuotaIndicatorLocation ?: return@onIsModified false
                val selectedDisplayMode = displayModeComboBox?.selectedItem as? QuotaDisplayMode ?: return@onIsModified false
                val state = QuotaSettingsState.getInstance()
                selectedLocation != state.location() ||
                    QuotaDisplayMode.sanitizeFor(selectedLocation, selectedDisplayMode) != state.displayMode()
            }
        }.apply {
            preferredFocusedComponent = locationComboBox
        }

        // Codex tab content
        val codexConfigPanel = panel {
            row { cell(loginHeaderLabel!!) }
            row {
                cell(loginButton!!).gap(RightGap.SMALL)
                cell(cancelLoginButton!!).gap(RightGap.SMALL)
                cell(logoutButton!!)
            }
            row {
                cell(statusLabel!!).gap(RightGap.SMALL)
                cell(copyUrlButton!!)
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
                    addToCenter(codexResponseViewer!!)
                },
            )
        }

        // OpenCode tab content
        val openCodeConfigPanel = panel {
            row {
                label("OpenCode Go").bold()
            }
            row("Session cookie:") {
                cell(openCodeCookieField!!)
                    .resizableColumn()
                    .align(AlignX.FILL)
                button("Save") {
                    val cookie = String(openCodeCookieField!!.password)
                    if (cookie.isNotBlank()) {
                        OpenCodeSessionCookieStore.getInstance().save(cookie)
                        openCodeCookieField!!.text = ""
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
            row {
                cell(openCodeStatusLabel!!)
            }
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
                    addToCenter(openCodeResponseTabbedPane!!)
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
        openCodeRawViewer = null
        openCodeJsonViewer = null
        openCodeResponseTabbedPane = null
        loginHeaderLabel = null
        statusLabel = null
        statusLabelDefaultForeground = null
        authStatusMessage = null
        locationComboBox = null
        displayModeComboBox = null
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

    private fun setStatusMessage(text: String, isError: Boolean = false) {
        authStatusMessage = AuthStatusMessage(text, isError)
    }

    private fun renderStatusMessage(message: AuthStatusMessage?) {
        val label = statusLabel ?: return
        label.text = message?.text.orEmpty()
        label.foreground = if (message?.isError == true) JBColor.RED else statusLabelDefaultForeground ?: label.foreground
        label.isVisible = !message?.text.isNullOrBlank()
    }

    private fun updateResponseArea() {
        val area = codexResponseViewer ?: return
        val json = QuotaUsageService.getInstance().getLastResponseJson()
        area.text = if (json.isNullOrBlank()) "No quota response yet." else json
        area.setCaretPosition(0)
    }

    private fun updateOpenCodeResponseArea() {
        val rawArea = openCodeRawViewer ?: return
        val jsonArea = openCodeJsonViewer ?: return
        val quota = QuotaUsageService.getInstance().getLastOpenCodeQuota()
        val error = QuotaUsageService.getInstance().getLastOpenCodeError()

        rawArea.text = when {
            error != null -> "Error: $error"
            quota?.rawJson.isNullOrBlank() -> "No OpenCode response yet."
            else -> quota.rawJson!!
        }
        rawArea.setCaretPosition(0)

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

    @Suppress("UsePropertyAccessSyntax")
    private fun createResponseViewer(): EditorTextField {
        val jsonLanguage = Language.findLanguageByID("JSON")
        val viewer = if (jsonLanguage != null) {
            LanguageTextField(jsonLanguage, null, "", false)
        } else {
            EditorTextField("", null, PlainTextFileType.INSTANCE)
        }

        return viewer.apply {
            setOneLineMode(false)
            isViewer = true
            setFontInheritedFromLAF(false)
            preferredSize = Dimension(1, JBUI.scale(220))
            minimumSize = Dimension(1, JBUI.scale(120))
            addSettingsProvider { editor ->
                editor.settings.apply {
                    isLineNumbersShown = false
                    isFoldingOutlineShown = false
                    isLineMarkerAreaShown = false
                    isIndentGuidesShown = false
                    additionalLinesCount = 0
                    additionalColumnsCount = 0
                    isRightMarginShown = false
                }
                editor.setHorizontalScrollbarVisible(true)
                editor.setVerticalScrollbarVisible(true)
            }
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
        openCodeCookieField?.text = ""
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
                label.text = "No session cookie configured"
                label.foreground = JBColor.GRAY
            }
            openCodeError != null -> {
                label.text = "Error: $openCodeError"
                label.foreground = JBColor.RED
            }
            openCodeQuota != null -> {
                label.text = "Connected - Go subscription active"
                label.foreground = JBColor.GREEN.darker()
            }
            else -> {
                label.text = "Session cookie stored securely"
                label.foreground = JBColor.GRAY
            }
        }
        label.isVisible = true
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
}

internal data class AuthStatusMessage(
    val text: String,
    val isError: Boolean = false,
)

internal data class QuotaSettingsAuthUiState(
    val headerText: String,
    val visibleStatusMessage: AuthStatusMessage?,
    val loginEnabled: Boolean,
    val cancelEnabled: Boolean,
    val logoutEnabled: Boolean,
) {
    companion object {
        fun create(loggedIn: Boolean, inProgress: Boolean, statusMessage: AuthStatusMessage?): QuotaSettingsAuthUiState {
            val stateText = when {
                inProgress -> "In progress"
                loggedIn -> "Logged in"
                else -> "Not logged in"
            }
            val visibleStatusMessage = statusMessage ?: if (inProgress) {
                AuthStatusMessage("Complete the login in your browser.")
            } else {
                null
            }
            return QuotaSettingsAuthUiState(
                headerText = "Login ($stateText)",
                visibleStatusMessage = visibleStatusMessage,
                loginEnabled = !inProgress && !loggedIn,
                cancelEnabled = inProgress,
                logoutEnabled = loggedIn,
            )
        }
    }
}
