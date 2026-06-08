package de.moritzf.quota.idea.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.openai.OpenAiProxyApiKeyStore
import de.moritzf.quota.idea.openai.OpenAiProxyService
import de.moritzf.quota.idea.ui.QuotaUiUtil
import java.awt.Dimension
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants

/**
 * OpenAI Codex settings tab.
 */
internal class OpenAiSettingsPanel : BorderLayoutPanel() {
    val openAiHideFromPopupCheckBox = com.intellij.ui.components.JBCheckBox("Hide from quota popup")
    val openAiProxyEnabledCheckBox = com.intellij.ui.components.JBCheckBox("Enable local OpenAI-compatible proxy")
    private val statusLabel = JBLabel().apply { isVisible = false }
    private val proxyStatusLabel = JBLabel().apply { isVisible = false }
    private val loginButton = createActionLink("Log In")
    private val cancelLoginButton = createActionLink("Cancel Login")
    private val logoutButton = createActionLink("Log Out")
    private val copyProxyBaseUrlButton = JButton("Copy Base URL", AllIcons.Actions.Copy)
    private val copyProxyApiKeyButton = JButton("Copy API Key", AllIcons.Actions.Copy)
    private val regenerateProxyApiKeyButton = JButton("Regenerate API Key")
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
    private val codexResponseViewer = createResponseViewer()
    private var authUrl: String? = null
    private var authStatusMessage: AuthStatusMessage? = null

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
            copyToClipboard(OpenAiProxyService.localBaseUrl(proxyPort()))
            setProxyStatus("Copied proxy base URL", AuthStatusKind.CONNECTED)
        }

        copyProxyApiKeyButton.addActionListener {
            copyProxyApiKey()
        }

        regenerateProxyApiKeyButton.addActionListener {
            val confirmed = Messages.showYesNoDialog(
                this,
                "Regenerate the local proxy API key? Existing clients must be updated to the new key.",
                "Regenerate Proxy API Key",
                Messages.getWarningIcon(),
            ) == Messages.YES
            if (confirmed) {
                regenerateProxyApiKey()
            }
        }

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
            QuotaUsageService.getInstance().clearCodexUsageData("Not logged in")
            authStatusMessage = AuthStatusMessage("Logged out", false, AuthStatusKind.DISCONNECTED)
            updateAuthUi()
            updateAccountFields()
            onLogout?.invoke()
        }

        val codexConfigPanel = panel {
            row {
                cell(openAiHideFromPopupCheckBox)
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
            separator()
            row {
                cell(openAiProxyEnabledCheckBox)
            }
            row("Port:") {
                cell(proxyPortField)
            }
            row {
                cell(proxyStatusLabel)
            }
            row {
                cell(copyProxyBaseUrlButton).gap(RightGap.SMALL)
                cell(copyProxyApiKeyButton).gap(RightGap.SMALL)
                cell(regenerateProxyApiKeyButton)
            }
            separator()
        }

        addToTop(codexConfigPanel)
        addToCenter(
            BorderLayoutPanel().apply {
                addToTop(JBLabel("Last quota response:"))
                addToCenter(createResponseViewerPanel(codexResponseViewer))
            },
        )
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
        emailField.text = if (authService.isLoggedIn(QuotaProviderType.OPEN_AI)) QuotaUsageService.getInstance().getLastQuota()?.email else null.orEmpty()
    }

    fun updateResponseArea() {
        val json = QuotaUsageService.getInstance().getLastResponseJson()
        codexResponseViewer.text = if (json.isNullOrBlank()) "No quota response yet." else json
        codexResponseViewer.setCaretPosition(0)
    }

    fun updateProxyFields() {
        val settings = QuotaSettingsState.getInstance()
        openAiProxyEnabledCheckBox.isSelected = settings.openAiProxyEnabled
        proxyPortField.text = OpenAiProxyService.sanitizePort(settings.openAiProxyPort).toString()
        updateProxyStatus()
    }

    fun updateProxyStatus() {
        val configuredEnabled = QuotaSettingsState.getInstance().openAiProxyEnabled
        val configuredPort = OpenAiProxyService.sanitizePort(QuotaSettingsState.getInstance().openAiProxyPort)
        val requestedPort = proxyPortOrNull()
        if (requestedPort == null) {
            setProxyStatus("Proxy port must be between 1 and 65535", AuthStatusKind.DISCONNECTED)
            return
        }
        if (!openAiProxyEnabledCheckBox.isSelected) {
            setProxyStatus("Proxy disabled", AuthStatusKind.DISCONNECTED)
            return
        }
        if (!configuredEnabled || configuredPort != requestedPort) {
            setProxyStatus("Apply settings to start proxy at ${OpenAiProxyService.localBaseUrl(requestedPort)}", AuthStatusKind.PENDING)
            return
        }

        val proxyStatus = OpenAiProxyService.getInstance().status()
        val loggedIn = QuotaAuthService.getInstance().isLoggedIn(QuotaProviderType.OPEN_AI)
        when {
            proxyStatus.running && loggedIn -> setProxyStatus("Proxy running at ${proxyStatus.baseUrl}", AuthStatusKind.CONNECTED)
            proxyStatus.running -> setProxyStatus("Proxy running; OpenAI login required for requests", AuthStatusKind.PENDING)
            proxyStatus.error != null -> setProxyStatus("Proxy error: ${proxyStatus.error}", AuthStatusKind.DISCONNECTED)
            else -> setProxyStatus("Proxy starting at ${proxyStatus.baseUrl}", AuthStatusKind.PENDING)
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

    private fun copyProxyApiKey() {
        setProxyStatus("Loading proxy API key...", AuthStatusKind.PENDING)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { OpenAiProxyApiKeyStore.getInstance().ensureApiKeyBlocking() }
            ApplicationManager.getApplication().invokeLater({
                result.fold(
                    onSuccess = { apiKey ->
                        copyToClipboard(apiKey)
                        setProxyStatus("Copied proxy API key", AuthStatusKind.CONNECTED)
                    },
                    onFailure = { error ->
                        setProxyStatus("Could not load proxy API key: ${error.message ?: error::class.java.simpleName}", AuthStatusKind.DISCONNECTED)
                    },
                )
            }, ModalityState.stateForComponent(this@OpenAiSettingsPanel))
        }
    }

    private fun regenerateProxyApiKey() {
        setProxyStatus("Regenerating proxy API key...", AuthStatusKind.PENDING)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { OpenAiProxyApiKeyStore.getInstance().regenerateBlocking() }
            ApplicationManager.getApplication().invokeLater({
                result.fold(
                    onSuccess = { apiKey ->
                        copyToClipboard(apiKey)
                        setProxyStatus("Regenerated and copied proxy API key", AuthStatusKind.CONNECTED)
                    },
                    onFailure = { error ->
                        setProxyStatus("Could not regenerate proxy API key: ${error.message ?: error::class.java.simpleName}", AuthStatusKind.DISCONNECTED)
                    },
                )
            }, ModalityState.stateForComponent(this@OpenAiSettingsPanel))
        }
    }

    private fun copyToClipboard(text: String) {
        val selection = StringSelection(text)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
    }

    private fun setProxyStatus(text: String, kind: AuthStatusKind) {
        proxyStatusLabel.text = formatStatusText(text, kind)
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
}
