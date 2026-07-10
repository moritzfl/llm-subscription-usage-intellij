package de.moritzf.quota.idea.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.quota.claude.ClaudeQuota
import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.shared.JsonSupport
import kotlinx.serialization.encodeToString
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants

/**
 * Claude settings tab.
 *
 * Anthropic has no device-code flow. Login is browser OAuth + paste of the
 * authorization code shown on the Claude callback page (same pattern as the
 * Claude Code / OpenCode Anthropic auth plugins).
 */
internal class ClaudeSettingsPanel(
    private val modalityComponentProvider: () -> JComponent?,
    private val statusLabelDefaultForeground: Color? = null,
) : ProviderSettingsPanel() {
    override val hideFromPopupCheckBox = com.intellij.ui.components.JBCheckBox("Hide from quota popup")
    private val statusLabel = JBLabel().apply { isVisible = false }
    private val loginButton = createActionLink("Log In with Claude")
    private val cancelLoginButton = createActionLink("Cancel Login")
    private val logoutButton = createActionLink("Log Out")
    private val copyUrlButton = JButton("Copy URL", AllIcons.Actions.Copy).apply {
        isVisible = false
        toolTipText = "Copy login URL to clipboard"
    }
    private val authCodeField = JBPasswordField().apply {
        columns = 40
        toolTipText = "Paste the Claude callback URL, code#state, or code=...&state=... after browser login"
    }
    private val submitCodeButton = JButton("Submit Code")
    private val jsonViewer = createResponseViewer()
    private var authUrl: String? = null
    private var authStatusMessage: AuthStatusMessage? = null

    init {
        copyUrlButton.addActionListener {
            authUrl?.takeIf { it.isNotBlank() }?.let(::copyToClipboard)
        }

        submitCodeButton.addActionListener { submitAuthCode() }
        authCodeField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) {
                if (event.keyCode == KeyEvent.VK_ENTER) {
                    submitAuthCode()
                }
            }
        })

        loginButton.addActionListener {
            val authService = QuotaAuthService.getInstance()
            if (authService.isLoggedIn(QuotaProviderType.CLAUDE)) {
                updateAuthUi()
                return@addActionListener
            }
            loginButton.isEnabled = false
            authCodeField.text = ""
            authStatusMessage = AuthStatusMessage(
                "Opening browser... complete Claude login, then paste the authorization code below.",
                false,
                AuthStatusKind.PENDING,
            )
            updateAuthUi()
            authService.startLoginFlow(type = QuotaProviderType.CLAUDE, callback = { result ->
                ApplicationManager.getApplication().invokeLater({
                    authStatusMessage = if (result.success) {
                        authCodeField.text = ""
                        AuthStatusMessage("Connected to Claude", false, AuthStatusKind.CONNECTED)
                    } else {
                        AuthStatusMessage(result.message ?: "Login failed", true, AuthStatusKind.DISCONNECTED)
                    }
                    loginButton.isEnabled = true
                    updateAuthUi()
                    if (result.success) {
                        QuotaUsageService.getInstance().refreshAsync(QuotaProviderType.CLAUDE)
                    }
                }, ModalityState.stateForComponent(modalityComponentProvider() ?: this@ClaudeSettingsPanel))
            }, onAuthUrl = { url ->
                ApplicationManager.getApplication().invokeLater({
                    authUrl = url
                    copyUrlButton.isVisible = true
                    authCodeField.requestFocusInWindow()
                    updateAuthUi()
                }, ModalityState.stateForComponent(modalityComponentProvider() ?: this@ClaudeSettingsPanel))
            })
            updateAuthUi()
        }

        cancelLoginButton.addActionListener {
            val aborted = QuotaAuthService.getInstance().abortLogin(QuotaProviderType.CLAUDE, "Login canceled")
            authCodeField.text = ""
            authStatusMessage = AuthStatusMessage(
                if (aborted) "Login canceled" else "No login in progress",
                false,
                if (aborted) AuthStatusKind.PENDING else AuthStatusKind.DISCONNECTED,
            )
            updateAuthUi()
        }

        logoutButton.addActionListener {
            QuotaAuthService.getInstance().clearCredentials(QuotaProviderType.CLAUDE)
            QuotaUsageService.getInstance().clearUsageData(QuotaProviderType.CLAUDE)
            authCodeField.text = ""
            authStatusMessage = AuthStatusMessage("Logged out of Claude", false, AuthStatusKind.DISCONNECTED)
            updateAuthUi()
        }

        val configPanel = panel {
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
            row {
                label("Claude has no device-code login. Click Log In, finish the browser flow, then paste the authorization code from the Claude page (full URL or code#state).")
            }
            row("Authorization code:") {
                cell(authCodeField)
                    .resizableColumn()
                    .align(AlignX.FILL)
                    .gap(RightGap.SMALL)
                cell(submitCodeButton)
            }
            separator()
        }

        addToTop(configPanel)
        addToCenter(
            BorderLayoutPanel().apply {
                addToTop(JBLabel("Last quota response:"))
                addToCenter(createResponseViewerPanel(jsonViewer))
            },
        )
    }

    private fun submitAuthCode() {
        val input = String(authCodeField.password).trim()
        if (input.isEmpty()) {
            authStatusMessage = AuthStatusMessage(
                "Paste the Claude authorization code first.",
                true,
                AuthStatusKind.PENDING,
            )
            updateAuthUi()
            return
        }
        if (!QuotaAuthService.getInstance().isLoginInProgress(QuotaProviderType.CLAUDE)) {
            authStatusMessage = AuthStatusMessage(
                "Click Log In with Claude first, then paste the code from that browser session.",
                true,
                AuthStatusKind.DISCONNECTED,
            )
            updateAuthUi()
            return
        }
        val error = QuotaAuthService.getInstance().completePastedCallback(QuotaProviderType.CLAUDE, input)
        authStatusMessage = if (error == null) {
            AuthStatusMessage("Exchanging authorization code...", false, AuthStatusKind.PENDING)
        } else {
            AuthStatusMessage(error, true, AuthStatusKind.PENDING)
        }
        updateAuthUi()
    }

    override fun updateFields() {
        updateAuthUi()
        updateResponseArea()
    }

    override fun updateStatus() {
        updateAuthUi()
    }

    private fun updateAuthUi() {
        val authService = QuotaAuthService.getInstance()
        val loggedIn = authService.isLoggedIn(QuotaProviderType.CLAUDE)
        val inProgress = authService.isLoginInProgress(QuotaProviderType.CLAUDE)
        val quota = QuotaUsageService.getInstance().getLastQuota(QuotaProviderType.CLAUDE) as? ClaudeQuota
        val error = QuotaUsageService.getInstance().getLastError(QuotaProviderType.CLAUDE)
        val uiState = QuotaSettingsAuthUiState.create(loggedIn, inProgress, authStatusMessage)
        loginButton.isEnabled = uiState.loginEnabled
        cancelLoginButton.isEnabled = uiState.cancelEnabled
        logoutButton.isEnabled = uiState.logoutEnabled
        authCodeField.isEnabled = inProgress || !loggedIn
        submitCodeButton.isEnabled = inProgress
        val status = when {
            inProgress -> uiState.visibleStatusMessage
                ?: AuthStatusMessage(
                    "Complete Claude login in your browser, then paste the authorization code below.",
                    false,
                    AuthStatusKind.PENDING,
                )
            error != null -> AuthStatusMessage("Error: $error", true, AuthStatusKind.DISCONNECTED)
            quota != null -> AuthStatusMessage("Connected to Claude", false, AuthStatusKind.CONNECTED)
            loggedIn -> AuthStatusMessage("Claude login stored securely", false, AuthStatusKind.CONNECTED)
            else -> AuthStatusMessage("Not configured", true, AuthStatusKind.DISCONNECTED)
        }
        statusLabel.text = formatStatusText(status.text, status.kind)
        statusLabel.foreground = statusLabelDefaultForeground ?: statusLabel.foreground
        statusLabel.isVisible = true
        if (!inProgress) {
            copyUrlButton.isVisible = false
            authUrl = null
        } else {
            copyUrlButton.isVisible = !authUrl.isNullOrBlank()
        }
    }

    override fun updateResponseArea() {
        val quota = QuotaUsageService.getInstance().getLastQuota(QuotaProviderType.CLAUDE) as? ClaudeQuota
        val error = QuotaUsageService.getInstance().getLastError(QuotaProviderType.CLAUDE)
        val rawJson = QuotaUsageService.getInstance().getLastResponseJson(QuotaProviderType.CLAUDE)
        jsonViewer.text = when {
            error != null && !rawJson.isNullOrBlank() -> "Error: $error\n\n$rawJson"
            error != null -> "Error: $error"
            quota == null -> "No Claude response yet."
            !rawJson.isNullOrBlank() -> rawJson
            else -> runCatching { JsonSupport.json.encodeToString(ClaudeQuota.serializer(), quota) }
                .getOrElse { "Could not serialize response: ${it.message}" }
        }
        jsonViewer.setCaretPosition(0)
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
        return ActionLink(text).apply { autoHideOnDisable = false }
    }

    private fun copyToClipboard(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
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
