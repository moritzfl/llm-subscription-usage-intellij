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
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.ui.QuotaUiUtil
import java.awt.Dimension
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants

/**
 * OpenAI Codex settings tab.
 */
internal class OpenAiSettingsPanel : BorderLayoutPanel() {
    val openAiHideFromPopupCheckBox = com.intellij.ui.components.JBCheckBox("Hide from quota popup")
    private val statusLabel = JBLabel().apply { isVisible = false }
    private val loginButton = createActionLink("Log In")
    private val cancelLoginButton = createActionLink("Cancel Login")
    private val logoutButton = createActionLink("Log Out")
    private val copyUrlButton = JButton("Copy URL", AllIcons.Actions.Copy).apply {
        isVisible = false
        toolTipText = "Copy login URL to clipboard"
    }
    private val accountIdField = JBTextField().apply { isEditable = false }
    private val emailField = JBTextField().apply { isEditable = false }
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
                val selection = StringSelection(url)
                Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
            }
        }

        loginButton.addActionListener {
            val authService = QuotaAuthService.getInstance()
            if (authService.isLoggedIn()) {
                updateAuthUi()
                return@addActionListener
            }

            loginButton.isEnabled = false
            authStatusMessage = AuthStatusMessage("Opening browser...", false, AuthStatusKind.PENDING)
            updateAuthUi()
            authService.startLoginFlow(callback = { result ->
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
            val aborted = QuotaAuthService.getInstance().abortLogin("Login canceled")
            authStatusMessage = AuthStatusMessage(
                if (aborted) "Login canceled" else "No login in progress",
                false,
                if (aborted) AuthStatusKind.PENDING else AuthStatusKind.DISCONNECTED
            )
            updateAuthUi()
            onCancelLogin?.invoke()
        }

        logoutButton.addActionListener {
            QuotaAuthService.getInstance().clearCredentials()
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
        val loggedIn = authService.isLoggedIn()
        val inProgress = authService.isLoginInProgress()
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
        accountIdField.text = authService.getAccountId().orEmpty()
        emailField.text = if (authService.isLoggedIn()) QuotaUsageService.getInstance().getLastQuota()?.email else null.orEmpty()
    }

    fun updateResponseArea() {
        val json = QuotaUsageService.getInstance().getLastResponseJson()
        codexResponseViewer.text = if (json.isNullOrBlank()) "No quota response yet." else json
        codexResponseViewer.setCaretPosition(0)
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

    private fun formatStatusText(text: String, kind: AuthStatusKind): String {
        val color = when (kind) {
            AuthStatusKind.CONNECTED -> "#4CAF50"
            AuthStatusKind.DISCONNECTED -> "#F44336"
            AuthStatusKind.PENDING -> "#FFC107"
        }
        return "<html><span style='color:$color'>●</span>&nbsp;${QuotaUiUtil.escapeHtml(text)}</html>"
    }
}
