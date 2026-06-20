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
import de.moritzf.quota.kimi.KimiQuota
import de.moritzf.quota.idea.auth.AuthService
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.kimi.KimiAuthService
import de.moritzf.quota.idea.kimi.KimiCredentialsStore
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

internal class KimiSettingsPanel(
    private val modalityComponentProvider: () -> JComponent?,
    private val statusLabelDefaultForeground: Color? = null,
) : ProviderSettingsPanel() {
    override val hideFromPopupCheckBox = com.intellij.ui.components.JBCheckBox("Hide from quota popup")
    private val statusLabel = JBLabel().apply { isVisible = false }
    private val loginButton = createActionLink("Log In")
    private val cancelLoginButton = createActionLink("Cancel Login")
    private val logoutButton = createActionLink("Log Out")
    private val copyUrlButton = JButton("Copy URL", AllIcons.Actions.Copy).apply {
        isVisible = false
        toolTipText = "Copy Kimi verification URL to clipboard"
    }
    private val userCodeLabel = JBLabel().apply { isVisible = false }
    private val responseViewer = createResponseViewer()
    private var verificationUrl: String? = null
    private var authStatusMessage: AuthStatusMessage? = null

    init {
        copyUrlButton.addActionListener {
            val url = verificationUrl
            if (!url.isNullOrBlank()) {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(url), null)
            }
        }

        loginButton.addActionListener {
            val authService: AuthService = KimiAuthService.getInstance()
            if (authService.isLoggedIn()) {
                updateStatus()
                return@addActionListener
            }
            loginButton.isEnabled = false
            authStatusMessage = AuthStatusMessage("Opening browser...", false, AuthStatusKind.PENDING)
            updateStatus()
            authService.startLoginFlow(callback = { result ->
                ApplicationManager.getApplication().invokeLater({
                    authStatusMessage = if (result.success) {
                        AuthStatusMessage("Connected", false, AuthStatusKind.CONNECTED)
                    } else {
                        AuthStatusMessage(result.message ?: "Login failed", true, AuthStatusKind.DISCONNECTED)
                    }
                    loginButton.isEnabled = true
                    updateStatus()
                    if (result.success) {
                        QuotaUsageService.getInstance().refreshAsync(QuotaProviderType.KIMI)
                    }
                }, ModalityState.stateForComponent(modalityComponentProvider() ?: this))
            }, onVerificationUrl = { url, userCode ->
                ApplicationManager.getApplication().invokeLater({
                    verificationUrl = url
                    copyUrlButton.isVisible = true
                    userCodeLabel.text = if (userCode.isBlank()) "" else "Kimi code: $userCode"
                    userCodeLabel.isVisible = userCode.isNotBlank()
                    authStatusMessage = AuthStatusMessage("Waiting for browser authorization...", false, AuthStatusKind.PENDING)
                    updateStatus()
                }, ModalityState.stateForComponent(modalityComponentProvider() ?: this))
            })
            updateStatus()
        }

        cancelLoginButton.addActionListener {
            val aborted = (KimiAuthService.getInstance() as AuthService).abortLogin("Login canceled")
            authStatusMessage = AuthStatusMessage(
                if (aborted) "Login canceled" else "No login in progress",
                false,
                if (aborted) AuthStatusKind.PENDING else AuthStatusKind.DISCONNECTED,
            )
            updateStatus()
        }

        logoutButton.addActionListener {
            setPending("Clearing credentials...")
            ApplicationManager.getApplication().executeOnPooledThread {
                (KimiAuthService.getInstance() as AuthService).clearCredentials()
                ApplicationManager.getApplication().invokeLater({
                    authStatusMessage = AuthStatusMessage("Logged out", false, AuthStatusKind.DISCONNECTED)
                    QuotaUsageService.getInstance().clearUsageData(QuotaProviderType.KIMI, "Not logged in")
                    updateStatus()
                }, ModalityState.stateForComponent(modalityComponentProvider() ?: this))
            }
        }

        addToTop(panel {
            row { cell(hideFromPopupCheckBox) }
            row { cell(statusLabel).gap(RightGap.SMALL); cell(copyUrlButton) }
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

    override fun updateFields() {
        KimiCredentialsStore.getInstance().load(onLoaded = ::refreshAfterCredentialsLoad)
        updateStatus()
    }

    override fun updateStatus() {
        val store = KimiCredentialsStore.getInstance()
        val credentials = store.load(onLoaded = ::refreshAfterCredentialsLoad)
        val inProgress = (KimiAuthService.getInstance() as AuthService).isLoginInProgress()
        val quota = QuotaUsageService.getInstance().getLastQuota(QuotaProviderType.KIMI) as? KimiQuota
        val error = QuotaUsageService.getInstance().getLastError(QuotaProviderType.KIMI)
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
            copyUrlButton.isVisible = false
            userCodeLabel.isVisible = false
            userCodeLabel.text = ""
        }
    }

    override fun updateResponseArea() {
        val raw = QuotaUsageService.getInstance().getLastResponseJson(QuotaProviderType.KIMI)
        val error = QuotaUsageService.getInstance().getLastError(QuotaProviderType.KIMI)
        responseViewer.text = when {
            error != null && !raw.isNullOrBlank() -> "Error: $error\n\n$raw"
            error != null -> "Error: $error"
            raw.isNullOrBlank() -> "No Kimi response yet."
            else -> raw
        }
        responseViewer.setCaretPosition(0)
    }

    private fun refreshAfterCredentialsLoad() {
        updateFields()
        updateResponseArea()
    }

    private fun setPending(text: String) {
        authStatusMessage = AuthStatusMessage(text, false, AuthStatusKind.PENDING)
        statusLabel.text = formatStatusText(text, AuthStatusKind.PENDING)
        statusLabel.isVisible = true
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
