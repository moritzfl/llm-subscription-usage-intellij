package de.moritzf.quota.idea.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.idea.openai.OpenAiProxyApiKeyStore
import de.moritzf.quota.idea.openai.OpenAiProxyService
import de.moritzf.quota.idea.ui.QuotaUiUtil
import java.awt.Dimension
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JTabbedPane
import javax.swing.JToggleButton
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.JTextComponent

/**
 * OpenAI Codex settings tab.
 */
internal class OpenAiSettingsPanel(
    private val modalityComponentProvider: () -> JComponent? = { null },
) : ProviderSettingsPanel() {
    override val hideFromPopupCheckBox = com.intellij.ui.components.JBCheckBox("Hide from quota popup")
    val openAiProxyEnabledCheckBox = com.intellij.ui.components.JBCheckBox("Enable local OpenAI-compatible proxy")
    val openAiProxyLogRequestsCheckBox = com.intellij.ui.components.JBCheckBox("Log requests and responses to disk")
    private val statusLabel = JBLabel().apply { isVisible = false }
    private val proxyStatusLabel = JBLabel().apply { isVisible = false }
    private val proxyApiKeyHintLabel = JBLabel().apply { isVisible = false }
    private val loginButton = createActionLink("Log In")
    private val cancelLoginButton = createActionLink("Cancel Login")
    private val logoutButton = createActionLink("Log Out")
    private val copyProxyBaseUrlButton = JButton("Copy Base URL", AllIcons.Actions.Copy).apply {
        toolTipText = "Copy the proxy base URL to the clipboard"
    }
    private val copyProxyApiKeyButton = JButton("Copy", AllIcons.Actions.Copy).apply {
        toolTipText = "Copy the API key to the clipboard"
        accessibleContext.accessibleName = "Copy API key"
    }
    private val generateProxyApiKeyButton = JButton("Generate").apply {
        toolTipText = "Generate a new API key; apply settings to save it"
        accessibleContext.accessibleName = "Generate API key"
    }
    private val copyUrlButton = JButton("Copy URL", AllIcons.Actions.Copy).apply {
        isVisible = false
        toolTipText = "Copy login URL to clipboard"
    }
    private val accountIdField = JBTextField().apply { isEditable = false }
    private val emailField = JBTextField().apply { isEditable = false }
    private val proxyPortField = JBTextField().apply {
        columns = 6
        toolTipText = "Loopback port for the local proxy server"
    }
    private val proxyApiKeyField = JBPasswordField().apply {
        columns = 40
        toolTipText = "Local API key accepted by the OpenAI-compatible proxy"
    }
    private val hiddenProxyApiKeyEchoChar = proxyApiKeyField.echoChar
    private val toggleProxyApiKeyVisibilityButton = JToggleButton(AllIcons.Actions.Show).apply {
        isFocusable = false
        toolTipText = "Show API key"
        accessibleContext.accessibleName = "Show API key"
    }
    private val codexResponseViewer = createResponseViewer()
    private val proxyApiKeyLoadGeneration = AtomicLong(0)
    private val proxyStatusRefreshTimer = Timer(PROXY_STATUS_REFRESH_MILLIS) { updateProxyStatus() }.apply {
        isRepeats = true
    }
    private val copiedFeedbackTimers = HashMap<JButton, Timer>()
    private var authUrl: String? = null
    private var authStatusMessage: AuthStatusMessage? = null
    private var savedProxyApiKey: String? = null
    private var proxyApiKeyLoading = false
    private var proxyApiKeyLoadError: String? = null

    var onLoginStarted: (() -> Unit)? = null
    var onLoginResult: ((Boolean, String?) -> Unit)? = null
    var onAuthUrlReceived: ((String) -> Unit)? = null
    var onCancelLogin: (() -> Unit)? = null
    var onLogout: (() -> Unit)? = null

    init {
        copyUrlButton.addActionListener {
            val url = authUrl
            if (!url.isNullOrBlank()) {
                copyToClipboard(url)
            }
        }

        copyProxyBaseUrlButton.addActionListener {
            val status = OpenAiProxyService.getInstance().status()
            val baseUrl = if (status.running) status.baseUrl else OpenAiProxyService.localBaseUrl(proxyPort())
            copyToClipboard(baseUrl)
            showCopiedFeedback(copyProxyBaseUrlButton)
        }

        copyProxyApiKeyButton.addActionListener {
            val apiKey = proxyApiKey() ?: return@addActionListener
            copyToClipboard(apiKey)
            showCopiedFeedback(copyProxyApiKeyButton)
        }

        generateProxyApiKeyButton.addActionListener {
            setProxyApiKeyText(OpenAiProxyApiKeyStore.getInstance().generateApiKeyForEditing())
        }

        toggleProxyApiKeyVisibilityButton.addActionListener {
            updateProxyApiKeyVisibility()
        }

        openAiProxyEnabledCheckBox.addItemListener {
            updateProxyStatus()
        }

        openAiProxyLogRequestsCheckBox.addItemListener {
            updateProxyStatus()
        }

        onDocumentChange(proxyPortField) {
            updateProxyStatus()
        }

        onDocumentChange(proxyApiKeyField) {
            updateProxyApiKeyHint()
            updateProxyStatus()
        }

        // Freeze the copy buttons at their natural width so the transient "Copied" label does not shift the layout.
        copyProxyBaseUrlButton.preferredSize = copyProxyBaseUrlButton.preferredSize
        copyProxyApiKeyButton.preferredSize = copyProxyApiKeyButton.preferredSize

        loginButton.addActionListener {
            val authService = QuotaAuthService.getInstance()
            if (authService.isLoggedIn(QuotaProviderType.OPEN_AI)) {
                updateAuthUi()
                return@addActionListener
            }

            loginButton.isEnabled = false
            authStatusMessage = AuthStatusMessage("Opening browser...", false, AuthStatusKind.PENDING)
            updateAuthUi()
            authService.startLoginFlow(type = QuotaProviderType.OPEN_AI, callback = { result ->
                ApplicationManager.getApplication().invokeLater({
                    authStatusMessage = if (result.success) {
                        AuthStatusMessage("Connected", false, AuthStatusKind.CONNECTED)
                    } else {
                        AuthStatusMessage(result.message ?: "Login failed", true, AuthStatusKind.DISCONNECTED)
                    }
                    onLoginResult?.invoke(result.success, result.message)
                    loginButton.isEnabled = true
                    updateAuthUi()
                    updateAccountFields()
                    if (result.success) {
                        QuotaUsageService.getInstance().refreshNowAsync()
                    }
                }, ModalityState.stateForComponent(this@OpenAiSettingsPanel))
            }, onAuthUrl = { url ->
                ApplicationManager.getApplication().invokeLater({
                    authUrl = url
                    copyUrlButton.isVisible = true
                    onAuthUrlReceived?.invoke(url)
                }, ModalityState.stateForComponent(this@OpenAiSettingsPanel))
            })
            updateAuthUi()
        }

        cancelLoginButton.addActionListener {
            val aborted = QuotaAuthService.getInstance().abortLogin(QuotaProviderType.OPEN_AI, "Login canceled")
            authStatusMessage = AuthStatusMessage(
                if (aborted) "Login canceled" else "No login in progress",
                false,
                if (aborted) AuthStatusKind.PENDING else AuthStatusKind.DISCONNECTED
            )
            updateAuthUi()
            onCancelLogin?.invoke()
        }

        logoutButton.addActionListener {
            QuotaAuthService.getInstance().clearCredentials(QuotaProviderType.OPEN_AI)
            QuotaUsageService.getInstance().clearUsageData(QuotaProviderType.OPEN_AI, "Not logged in")
            authStatusMessage = AuthStatusMessage("Logged out", false, AuthStatusKind.DISCONNECTED)
            updateAuthUi()
            updateAccountFields()
            onLogout?.invoke()
        }

        val usageTrackingConfigPanel = panel {
            row {
                cell(hideFromPopupCheckBox)
            }
            row {
                cell(statusLabel).gap(RightGap.SMALL)
                cell(copyUrlButton)
            }
            row {
                cell(loginButton).gap(RightGap.SMALL)
                cell(cancelLoginButton).gap(RightGap.SMALL)
                cell(logoutButton)
            }
            separator()
            row("Account ID:") {
                cell(accountIdField)
                    .resizableColumn()
                    .align(AlignX.FILL)
            }
            row("Email:") {
                cell(emailField)
                    .resizableColumn()
                    .align(AlignX.FILL)
            }
        }

        val usageTrackingPanel = BorderLayoutPanel().apply {
            isOpaque = false
            addToTop(usageTrackingConfigPanel)
            addToCenter(
                BorderLayoutPanel().apply {
                    addToTop(JBLabel("Last quota response:"))
                    addToCenter(createResponseViewerPanel(codexResponseViewer))
                },
            )
        }

        val proxyPanel = BorderLayoutPanel().apply {
            isOpaque = false
            addToTop(panel {
                row {
                    cell(openAiProxyEnabledCheckBox)
                        .comment("Serves an OpenAI-compatible API on localhost that forwards requests to your Codex subscription.")
                }
                indent {
                    row("Port:") {
                        cell(proxyPortField).gap(RightGap.SMALL)
                        cell(copyProxyBaseUrlButton)
                    }
                    row("API key:") {
                        cell(proxyApiKeyField)
                            .resizableColumn()
                            .align(AlignX.FILL)
                            .gap(RightGap.SMALL)
                        cell(toggleProxyApiKeyVisibilityButton).gap(RightGap.SMALL)
                        cell(copyProxyApiKeyButton).gap(RightGap.SMALL)
                        cell(generateProxyApiKeyButton)
                    }
                    row {
                        cell(openAiProxyLogRequestsCheckBox)
                            .comment(
                                "Writes full request and response bodies (prompts, tool output, file contents) to a " +
                                    "temp folder. Sensitive — leave off unless debugging. Logs are pruned automatically.",
                            )
                    }
                    row {
                        cell(proxyApiKeyHintLabel)
                    }
                    row {
                        cell(proxyStatusLabel)
                    }
                }
            })
        }

        addToCenter(JTabbedPane().apply {
            isOpaque = false
            addTab("Usage Tracking", usageTrackingPanel)
            addTab("Proxy", proxyPanel)
        })
    }

    override fun addNotify() {
        super.addNotify()
        updateProxyStatus()
        proxyStatusRefreshTimer.start()
    }

    override fun removeNotify() {
        proxyStatusRefreshTimer.stop()
        super.removeNotify()
    }

    override fun updateFields() {
        updateAuthUi()
        updateAccountFields()
        updateProxyFields()
    }

    override fun updateStatus() {
        updateAuthUi()
        updateAccountFields()
        updateProxyStatus()
    }

    fun updateAuthUi() {
        val authService = QuotaAuthService.getInstance()
        val loggedIn = authService.isLoggedIn(QuotaProviderType.OPEN_AI)
        val inProgress = authService.isLoginInProgress(QuotaProviderType.OPEN_AI)
        val uiState = QuotaSettingsAuthUiState.create(loggedIn, inProgress, authStatusMessage)
        loginButton.isEnabled = uiState.loginEnabled
        cancelLoginButton.isEnabled = uiState.cancelEnabled
        logoutButton.isEnabled = uiState.logoutEnabled
        statusLabel.text = uiState.visibleStatusMessage?.let { formatStatusText(it.text, it.kind) }.orEmpty()
        statusLabel.isVisible = !uiState.visibleStatusMessage?.text.isNullOrBlank()
        if (!inProgress) {
            copyUrlButton.isVisible = false
            authUrl = null
        }
    }

    fun updateAccountFields() {
        val authService = QuotaAuthService.getInstance()
        accountIdField.text = authService.getAccountId(QuotaProviderType.OPEN_AI).orEmpty()
        emailField.text = if (authService.isLoggedIn(QuotaProviderType.OPEN_AI)) (QuotaUsageService.getInstance().getLastQuota(QuotaProviderType.OPEN_AI) as? OpenAiCodexQuota)?.email else null.orEmpty()
    }

    override fun updateResponseArea() {
        val json = QuotaUsageService.getInstance().getLastResponseJson(QuotaProviderType.OPEN_AI)
        codexResponseViewer.text = if (json.isNullOrBlank()) "No quota response yet." else json
        codexResponseViewer.setCaretPosition(0)
    }

    fun updateProxyFields() {
        val settings = QuotaSettingsState.getInstance()
        openAiProxyEnabledCheckBox.isSelected = settings.openAiProxyEnabled
        openAiProxyLogRequestsCheckBox.isSelected = settings.openAiProxyLogRequests
        proxyPortField.text = OpenAiProxyService.sanitizePort(settings.openAiProxyPort).toString()
        loadProxyApiKeyField()
        updateProxyStatus()
    }

    /** Re-syncs the editable proxy fields with the freshly applied settings. */
    fun refreshAfterApply() {
        proxyPortField.text = OpenAiProxyService.sanitizePort(QuotaSettingsState.getInstance().openAiProxyPort).toString()
        updateProxyApiKeyHint()
        updateProxyStatus()
    }

    fun proxyApiKey(): String? = String(proxyApiKeyField.password).trim().ifBlank { null }

    fun isProxyApiKeyModified(): Boolean = proxyApiKey() != savedProxyApiKey

    fun isProxyPortModified(): Boolean {
        val configuredPort = OpenAiProxyService.sanitizePort(QuotaSettingsState.getInstance().openAiProxyPort)
        return proxyPortField.text.trim() != configuredPort.toString()
    }

    fun isProxyLogRequestsModified(): Boolean =
        openAiProxyLogRequestsCheckBox.isSelected != QuotaSettingsState.getInstance().openAiProxyLogRequests

    /**
     * Reflects only the proxy operating state (off / pending apply / starting / running / error).
     * Transient actions such as copying values must not write to this label.
     */
    fun updateProxyStatus() {
        updateProxyControlsEnabled()

        val settings = QuotaSettingsState.getInstance()
        val configuredEnabled = settings.openAiProxyEnabled
        val configuredPort = OpenAiProxyService.sanitizePort(settings.openAiProxyPort)
        val requestedPort = proxyPortOrNull()

        if (!openAiProxyEnabledCheckBox.isSelected) {
            if (configuredEnabled) {
                setProxyStatus("Apply settings to stop the proxy", ProxyRunState.PENDING)
            } else {
                setProxyStatus("Proxy is off", ProxyRunState.OFF)
            }
            return
        }
        if (requestedPort == null) {
            setProxyStatus("Port must be a number between 1 and 65535", ProxyRunState.ERROR)
            return
        }
        if (!configuredEnabled) {
            setProxyStatus("Apply settings to start the proxy at ${OpenAiProxyService.localBaseUrl(requestedPort)}", ProxyRunState.PENDING)
            return
        }
        if (configuredPort != requestedPort || isProxyApiKeyModified() || isProxyLogRequestsModified()) {
            setProxyStatus("Apply settings to restart the proxy with the updated configuration", ProxyRunState.PENDING)
            return
        }

        val proxyStatus = OpenAiProxyService.getInstance().status()
        val loggedIn = QuotaAuthService.getInstance().isLoggedIn(QuotaProviderType.OPEN_AI)
        when {
            proxyStatus.running && loggedIn -> setProxyStatus("Proxy running at ${proxyStatus.baseUrl}", ProxyRunState.RUNNING)
            proxyStatus.running -> setProxyStatus("Proxy running at ${proxyStatus.baseUrl} — log in to OpenAI to serve requests", ProxyRunState.PENDING)
            proxyStatus.error != null -> setProxyStatus("Proxy failed to start: ${proxyStatus.error}", ProxyRunState.ERROR)
            else -> setProxyStatus("Proxy starting at ${proxyStatus.baseUrl}...", ProxyRunState.PENDING)
        }
    }

    fun proxyPort(): Int = proxyPortOrNull() ?: OpenAiProxyService.DEFAULT_PORT

    private fun proxyPortOrNull(): Int? {
        val parsed = proxyPortField.text.trim().toIntOrNull() ?: return null
        return parsed.takeIf { it in 1..65535 }
    }

    private fun createResponseViewer(): com.intellij.ui.components.JBTextArea {
        return com.intellij.ui.components.JBTextArea().apply {
            isEditable = false
            lineWrap = false
            wrapStyleWord = false
            font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
            margin = JBUI.insets(6)
        }
    }

    private fun createResponseViewerPanel(viewer: com.intellij.ui.components.JBTextArea): JComponent {
        return JScrollPane(viewer).apply {
            preferredSize = Dimension(1, JBUI.scale(220))
            minimumSize = Dimension(1, JBUI.scale(120))
            border = JBUI.Borders.emptyTop(4)
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }
    }

    private fun createActionLink(text: String): ActionLink {
        return ActionLink(text).apply {
            autoHideOnDisable = false
        }
    }

    fun saveProxyApiKeyBlocking() {
        val apiKey = proxyApiKey()
        OpenAiProxyApiKeyStore.getInstance().saveBlocking(apiKey)
        savedProxyApiKey = apiKey
        proxyApiKeyLoadError = null
        updateProxyApiKeyHint()
    }

    private fun loadProxyApiKeyField() {
        val store = OpenAiProxyApiKeyStore.getInstance()
        val cachedApiKey = store.cachedApiKey()
        if (cachedApiKey != null && proxyApiKey() == savedProxyApiKey) {
            savedProxyApiKey = cachedApiKey
            setProxyApiKeyText(cachedApiKey)
        }

        val generation = proxyApiKeyLoadGeneration.incrementAndGet()
        val fieldValueAtRequest = proxyApiKey()
        val savedApiKeyAtRequest = savedProxyApiKey
        proxyApiKeyLoading = cachedApiKey == null
        proxyApiKeyLoadError = null
        updateProxyApiKeyHint()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { store.loadFreshBlocking() }
            ApplicationManager.getApplication().invokeLater({
                if (generation != proxyApiKeyLoadGeneration.get()) {
                    return@invokeLater
                }
                proxyApiKeyLoading = false
                result.fold(
                    onSuccess = { apiKey ->
                        savedProxyApiKey = apiKey
                        val currentFieldValue = proxyApiKey()
                        if (currentFieldValue == fieldValueAtRequest || currentFieldValue == savedApiKeyAtRequest) {
                            setProxyApiKeyText(apiKey)
                        }
                    },
                    onFailure = { error ->
                        if (cachedApiKey == null) {
                            proxyApiKeyLoadError =
                                "Could not load the API key from secure storage: ${error.message ?: error::class.java.simpleName}"
                        }
                    },
                )
                updateProxyApiKeyHint()
                updateProxyStatus()
            }, ModalityState.stateForComponent(modalityComponentProvider() ?: this@OpenAiSettingsPanel))
        }
    }

    private fun setProxyApiKeyText(apiKey: String?) {
        proxyApiKeyField.text = apiKey.orEmpty()
    }

    private fun updateProxyApiKeyVisibility() {
        val visible = toggleProxyApiKeyVisibilityButton.isSelected
        proxyApiKeyField.echoChar = if (visible) 0.toChar() else hiddenProxyApiKeyEchoChar
        val action = if (visible) "Hide" else "Show"
        toggleProxyApiKeyVisibilityButton.toolTipText = "$action API key"
        toggleProxyApiKeyVisibilityButton.accessibleContext.accessibleName = "$action API key"
    }

    private fun updateProxyControlsEnabled() {
        val enabled = openAiProxyEnabledCheckBox.isSelected
        proxyPortField.isEnabled = enabled
        proxyApiKeyField.isEnabled = enabled
        toggleProxyApiKeyVisibilityButton.isEnabled = enabled
        generateProxyApiKeyButton.isEnabled = enabled
        copyProxyBaseUrlButton.isEnabled = enabled
        copyProxyApiKeyButton.isEnabled = enabled && proxyApiKey() != null
        openAiProxyLogRequestsCheckBox.isEnabled = enabled
    }

    private fun updateProxyApiKeyHint() {
        val currentApiKey = proxyApiKey()
        val loadError = proxyApiKeyLoadError
        var isError = false
        val hint = when {
            proxyApiKeyLoading && currentApiKey == null -> "Loading API key from secure storage..."
            loadError != null -> {
                isError = true
                loadError
            }
            currentApiKey == null && savedProxyApiKey == null -> "No API key yet — generate one, then apply settings."
            !isProxyApiKeyModified() -> null
            currentApiKey == null -> "The API key will be removed from secure storage when settings are applied."
            else -> "New API key — apply settings to save it to secure storage."
        }
        proxyApiKeyHintLabel.foreground = if (isError) UIUtil.getErrorForeground() else UIUtil.getContextHelpForeground()
        proxyApiKeyHintLabel.text = hint.orEmpty()
        proxyApiKeyHintLabel.isVisible = hint != null
    }

    private fun showCopiedFeedback(button: JButton) {
        val runningTimer = copiedFeedbackTimers.remove(button)
        runningTimer?.stop()
        if (runningTimer == null) {
            button.putClientProperty(COPY_FEEDBACK_ORIGINAL_TEXT, button.text)
        }
        button.text = "Copied"
        button.icon = AllIcons.Actions.Checked
        val timer = Timer(COPY_FEEDBACK_MILLIS) {
            button.text = button.getClientProperty(COPY_FEEDBACK_ORIGINAL_TEXT) as? String ?: button.text
            button.icon = AllIcons.Actions.Copy
            copiedFeedbackTimers.remove(button)
        }
        timer.isRepeats = false
        copiedFeedbackTimers[button] = timer
        timer.start()
    }

    private fun onDocumentChange(field: JTextComponent, action: () -> Unit) {
        field.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = action()
            override fun removeUpdate(event: DocumentEvent) = action()
            override fun changedUpdate(event: DocumentEvent) = action()
        })
    }

    private fun copyToClipboard(text: String) {
        val selection = StringSelection(text)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
    }

    private fun setProxyStatus(text: String, state: ProxyRunState) {
        proxyStatusLabel.text = "<html><span style=\"color: ${state.colorHex}\">●</span>&nbsp;${QuotaUiUtil.escapeHtml(text)}</html>"
        proxyStatusLabel.isVisible = true
    }

    private fun formatStatusText(text: String, kind: AuthStatusKind): String {
        val color = when (kind) {
            AuthStatusKind.CONNECTED -> "#4CAF50"
            AuthStatusKind.DISCONNECTED -> "#F44336"
            AuthStatusKind.PENDING -> "#FFC107"
        }
        return "<html><span style=\"color: $color\">●</span>&nbsp;${QuotaUiUtil.escapeHtml(text)}</html>"
    }

    private enum class ProxyRunState(val colorHex: String) {
        RUNNING("#4CAF50"),
        OFF("#9E9E9E"),
        PENDING("#FFC107"),
        ERROR("#F44336"),
    }

    companion object {
        private const val PROXY_STATUS_REFRESH_MILLIS = 2_000
        private const val COPY_FEEDBACK_MILLIS = 1_500
        private const val COPY_FEEDBACK_ORIGINAL_TEXT = "OpenAiSettingsPanel.copyFeedbackOriginalText"
    }
}
