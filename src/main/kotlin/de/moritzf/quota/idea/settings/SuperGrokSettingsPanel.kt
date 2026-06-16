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
import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.supergrok.SuperGrokQuota
import kotlinx.serialization.encodeToString
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants

/** SuperGrok settings tab backed by plugin-managed xAI OAuth credentials. */
internal class SuperGrokSettingsPanel(
    private val modalityComponentProvider: () -> JComponent?,
    private val statusLabelDefaultForeground: Color? = null,
) : ProviderSettingsPanel() {
    override val hideFromPopupCheckBox = com.intellij.ui.components.JBCheckBox("Hide from quota popup")
    private val statusLabel = JBLabel().apply { isVisible = false }
    private val loginButton = createActionLink("Log In with xAI/Grok")
    private val cancelLoginButton = createActionLink("Cancel Login")
    private val logoutButton = createActionLink("Log Out")
    private val copyUrlButton = JButton("Copy URL", AllIcons.Actions.Copy).apply {
        isVisible = false
        toolTipText = "Copy login URL to clipboard"
    }
    private val jsonViewer = createResponseViewer()
    private var authUrl: String? = null
    private var authStatusMessage: AuthStatusMessage? = null

    init {
        copyUrlButton.addActionListener {
            val url = authUrl
            if (!url.isNullOrBlank()) {
                copyToClipboard(url)
            }
        }

        loginButton.addActionListener {
            val authService = QuotaAuthService.getInstance()
            if (authService.isLoggedIn(QuotaProviderType.SUPERGROK)) {
                updateAuthUi()
                return@addActionListener
            }
            loginButton.isEnabled = false
            authStatusMessage = AuthStatusMessage("Opening browser...", false, AuthStatusKind.PENDING)
            updateAuthUi()
            authService.startLoginFlow(type = QuotaProviderType.SUPERGROK, callback = { result ->
                ApplicationManager.getApplication().invokeLater({
                    authStatusMessage = if (result.success) {
                        AuthStatusMessage("Connected to xAI/Grok", false, AuthStatusKind.CONNECTED)
                    } else {
                        AuthStatusMessage(result.message ?: "Login failed", true, AuthStatusKind.DISCONNECTED)
                    }
                    loginButton.isEnabled = true
                    updateAuthUi()
                    if (result.success) {
                        QuotaUsageService.getInstance().refreshAsync(QuotaProviderType.SUPERGROK)
                    }
                }, ModalityState.stateForComponent(this@SuperGrokSettingsPanel))
            }, onAuthUrl = { url ->
                ApplicationManager.getApplication().invokeLater({
                    authUrl = url
                    copyUrlButton.isVisible = true
                }, ModalityState.stateForComponent(this@SuperGrokSettingsPanel))
            })
            updateAuthUi()
        }

        cancelLoginButton.addActionListener {
            val aborted = QuotaAuthService.getInstance().abortLogin(QuotaProviderType.SUPERGROK, "Login canceled")
            authStatusMessage = AuthStatusMessage(
                if (aborted) "Login canceled" else "No login in progress",
                false,
                if (aborted) AuthStatusKind.PENDING else AuthStatusKind.DISCONNECTED,
            )
            updateAuthUi()
        }

        logoutButton.addActionListener {
            QuotaAuthService.getInstance().clearCredentials(QuotaProviderType.SUPERGROK)
            QuotaUsageService.getInstance().clearUsageData(QuotaProviderType.SUPERGROK)
            authStatusMessage = AuthStatusMessage("Logged out of xAI/Grok", false, AuthStatusKind.DISCONNECTED)
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
                label("Uses plugin-managed xAI OAuth with the Grok CLI billing API. No local Grok CLI auth file is required.")
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

    override fun updateFields() {
        updateAuthUi()
        updateResponseArea()
    }

    override fun updateStatus() {
        updateAuthUi()
    }

    private fun updateAuthUi() {
        val authService = QuotaAuthService.getInstance()
        val loggedIn = authService.isLoggedIn(QuotaProviderType.SUPERGROK)
        val inProgress = authService.isLoginInProgress(QuotaProviderType.SUPERGROK)
        val quota = QuotaUsageService.getInstance().getLastQuota(QuotaProviderType.SUPERGROK) as? SuperGrokQuota
        val error = QuotaUsageService.getInstance().getLastError(QuotaProviderType.SUPERGROK)
        val uiState = QuotaSettingsAuthUiState.create(loggedIn, inProgress, authStatusMessage)
        loginButton.isEnabled = uiState.loginEnabled
        cancelLoginButton.isEnabled = uiState.cancelEnabled
        logoutButton.isEnabled = uiState.logoutEnabled
        val status = when {
            inProgress -> uiState.visibleStatusMessage ?: AuthStatusMessage("Complete the login in your browser.", false, AuthStatusKind.PENDING)
            error != null -> AuthStatusMessage("Error: $error", true, AuthStatusKind.DISCONNECTED)
            quota != null -> AuthStatusMessage("Connected to xAI/Grok", false, AuthStatusKind.CONNECTED)
            loggedIn -> AuthStatusMessage("xAI/Grok login stored securely", false, AuthStatusKind.CONNECTED)
            else -> AuthStatusMessage("Not configured", true, AuthStatusKind.DISCONNECTED)
        }
        statusLabel.text = formatStatusText(status.text, status.kind)
        statusLabel.foreground = statusLabelDefaultForeground ?: statusLabel.foreground
        statusLabel.isVisible = true
        if (!inProgress) {
            copyUrlButton.isVisible = false
            authUrl = null
        }
    }

    override fun updateResponseArea() {
        val quota = QuotaUsageService.getInstance().getLastQuota(QuotaProviderType.SUPERGROK) as? SuperGrokQuota
        val error = QuotaUsageService.getInstance().getLastError(QuotaProviderType.SUPERGROK)
        val rawJson = QuotaUsageService.getInstance().getLastResponseJson(QuotaProviderType.SUPERGROK)
        jsonViewer.text = when {
            error != null && !rawJson.isNullOrBlank() -> "Error: $error\n\n$rawJson"
            error != null -> "Error: $error"
            quota == null -> "No SuperGrok response yet."
            !rawJson.isNullOrBlank() -> rawJson
            else -> runCatching { JsonSupport.json.encodeToString(SuperGrokQuota.serializer(), quota) }
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
