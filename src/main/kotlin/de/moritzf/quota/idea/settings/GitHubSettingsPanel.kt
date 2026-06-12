package de.moritzf.quota.idea.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.github.GitHubQuota
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.github.GitHubAuthService
import de.moritzf.quota.idea.github.GitHubCredentialsStore
import de.moritzf.quota.idea.ui.QuotaUiUtil
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants

internal class GitHubSettingsPanel(
    private val modalityComponentProvider: () -> JComponent?,
    private val statusLabelDefaultForeground: Color? = null,
) : BorderLayoutPanel() {
    val gitHubHideFromPopupCheckBox = com.intellij.ui.components.JBCheckBox("Hide from quota popup")
    private val statusLabel = JBLabel().apply { isVisible = false }
    private val loginButton = createActionLink("Log In")
    private val cancelLoginButton = createActionLink("Cancel Login")
    private val logoutButton = createActionLink("Log Out")
    private val copyUrlButton = JButton("Copy URL", AllIcons.Actions.Copy).apply {
        isVisible = false
        toolTipText = "Copy GitHub verification URL to clipboard"
    }
    // The device flow requires the user to type this code at the verification URL,
    // so it gets its own copy button rather than just a label.
    private val copyCodeButton = JButton("Copy Code", AllIcons.Actions.Copy).apply {
        isVisible = false
        toolTipText = "Copy the GitHub device code to clipboard"
    }
    private val userCodeLabel = JBLabel().apply { isVisible = false }
    private val responseViewer = createResponseViewer()
    private var verificationUrl: String? = null
    private var userCode: String? = null
    private var authStatusMessage: AuthStatusMessage? = null

    init {
        copyUrlButton.addActionListener {
            verificationUrl?.takeIf { it.isNotBlank() }?.let { copyToClipboard(it) }
        }
        copyCodeButton.addActionListener {
            userCode?.takeIf { it.isNotBlank() }?.let { copyToClipboard(it) }
        }

        loginButton.addActionListener {
            val authService = GitHubAuthService.getInstance()
            if (authService.isLoggedIn()) {
                updateGitHubStatus()
                return@addActionListener
            }
            loginButton.isEnabled = false
            authStatusMessage = AuthStatusMessage("Requesting device code...", false, AuthStatusKind.PENDING)
            updateGitHubStatus()
            authService.startLoginFlow(callback = { result ->
                ApplicationManager.getApplication().invokeLater({
                    authStatusMessage = if (result.success) {
                        AuthStatusMessage("Connected", false, AuthStatusKind.CONNECTED)
                    } else {
                        AuthStatusMessage(result.message ?: "Login failed", true, AuthStatusKind.DISCONNECTED)
                    }
                    loginButton.isEnabled = true
                    updateGitHubStatus()
                    if (result.success) {
                        QuotaUsageService.getInstance().refreshAsync(QuotaProviderType.GITHUB)
                    }
                }, ModalityState.stateForComponent(modalityComponentProvider() ?: this))
            }, onVerificationUrl = { url, code ->
                ApplicationManager.getApplication().invokeLater({
                    verificationUrl = url
                    userCode = code
                    copyUrlButton.isVisible = true
                    copyCodeButton.isVisible = code.isNotBlank()
                    userCodeLabel.text = if (code.isBlank()) "" else "Enter code at $url: $code"
                    userCodeLabel.isVisible = code.isNotBlank()
                    authStatusMessage = AuthStatusMessage("Waiting for browser authorization...", false, AuthStatusKind.PENDING)
                    updateGitHubStatus()
                }, ModalityState.stateForComponent(modalityComponentProvider() ?: this))
            })
            updateGitHubStatus()
        }

        cancelLoginButton.addActionListener {
            val aborted = GitHubAuthService.getInstance().abortLogin("Login canceled")
            authStatusMessage = AuthStatusMessage(
                if (aborted) "Login canceled" else "No login in progress",
                false,
                if (aborted) AuthStatusKind.PENDING else AuthStatusKind.DISCONNECTED,
            )
            updateGitHubStatus()
        }

        logoutButton.addActionListener {
            setPending("Clearing credentials...")
            ApplicationManager.getApplication().executeOnPooledThread {
                GitHubAuthService.getInstance().clearCredentials()
                ApplicationManager.getApplication().invokeLater({
                    authStatusMessage = AuthStatusMessage("Logged out", false, AuthStatusKind.DISCONNECTED)
                    QuotaUsageService.getInstance().clearUsageData(QuotaProviderType.GITHUB, "Not logged in")
                    updateGitHubStatus()
                }, ModalityState.stateForComponent(modalityComponentProvider() ?: this))
            }
        }

        addToTop(panel {
            row { cell(gitHubHideFromPopupCheckBox) }
            row {
                cell(statusLabel).gap(RightGap.SMALL)
                cell(copyUrlButton).gap(RightGap.SMALL)
                cell(copyCodeButton)
            }
            row { cell(userCodeLabel) }
            row {
                cell(loginButton).gap(RightGap.SMALL)
                cell(cancelLoginButton).gap(RightGap.SMALL)
                cell(logoutButton)
            }
            separator()
        })
        addToCenter(BorderLayoutPanel().apply {
            addToTop(JBLabel("Last quota response:"))
            addToCenter(createResponseViewerPanel(responseViewer))
        })
    }

    fun updateGitHubFields() {
        GitHubCredentialsStore.getInstance().load(onLoaded = ::refreshAfterCredentialsLoad)
        updateGitHubStatus()
    }

    fun updateGitHubStatus() {
        val store = GitHubCredentialsStore.getInstance()
        val credentials = store.load(onLoaded = ::refreshAfterCredentialsLoad)
        val inProgress = GitHubAuthService.getInstance().isLoginInProgress()
        val quota = QuotaUsageService.getInstance().getLastQuota(QuotaProviderType.GITHUB) as? GitHubQuota
        val error = QuotaUsageService.getInstance().getLastError(QuotaProviderType.GITHUB)
        val fallbackMessage = when {
            !store.isLoaded() -> AuthStatusMessage("Loading credentials...", false, AuthStatusKind.PENDING)
            credentials?.isUsable() != true -> AuthStatusMessage("Not logged in", false, AuthStatusKind.DISCONNECTED)
            error != null -> AuthStatusMessage("Error: $error", true, AuthStatusKind.DISCONNECTED)
            quota != null -> AuthStatusMessage("Connected", false, AuthStatusKind.CONNECTED)
            else -> AuthStatusMessage("Credentials stored securely", false, AuthStatusKind.CONNECTED)
        }
        val visibleMessage = authStatusMessage?.takeIf { inProgress || it.isError || credentials?.isUsable() != true } ?: fallbackMessage
        statusLabel.text = formatStatusText(visibleMessage.text, visibleMessage.kind)
        statusLabel.foreground = statusLabelDefaultForeground ?: statusLabel.foreground
        statusLabel.isVisible = true
        loginButton.isEnabled = !inProgress && credentials?.isUsable() != true
        cancelLoginButton.isEnabled = inProgress
        logoutButton.isEnabled = !inProgress && credentials?.isUsable() == true
        if (!inProgress) {
            verificationUrl = null
            userCode = null
            copyUrlButton.isVisible = false
            copyCodeButton.isVisible = false
            userCodeLabel.isVisible = false
            userCodeLabel.text = ""
        }
    }

    fun updateGitHubResponseArea() {
        val raw = QuotaUsageService.getInstance().getLastResponseJson(QuotaProviderType.GITHUB)
        val error = QuotaUsageService.getInstance().getLastError(QuotaProviderType.GITHUB)
        responseViewer.text = when {
            error != null && !raw.isNullOrBlank() -> "Error: $error\n\n$raw"
            error != null -> "Error: $error"
            raw.isNullOrBlank() -> "No GitHub response yet."
            else -> raw
        }
        responseViewer.setCaretPosition(0)
    }

    private fun refreshAfterCredentialsLoad() {
        updateGitHubFields()
        updateGitHubResponseArea()
    }

    private fun setPending(text: String) {
        authStatusMessage = AuthStatusMessage(text, false, AuthStatusKind.PENDING)
        statusLabel.text = formatStatusText(text, AuthStatusKind.PENDING)
        statusLabel.isVisible = true
    }

    private fun copyToClipboard(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    private fun createResponseViewer() = com.intellij.ui.components.JBTextArea().apply {
        isEditable = false
        lineWrap = false
        wrapStyleWord = false
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
        margin = JBUI.insets(6)
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
        return "<html><span style=\"color: $color\">●</span>&nbsp;${QuotaUiUtil.escapeHtml(text)}</html>"
    }
}
