package de.moritzf.quota.idea.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.openai.proxy.OpenAiProxyServer
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.shared.DocumentModels
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JComponent

/**
 * OpenAI Codex settings tab.
 */
internal class OpenAiSettingsPanel(
    private val modalityComponentProvider: () -> JComponent? = { null },
) : ProviderSettingsPanel() {
    private val statusLabel = JBLabel().apply { isVisible = false }
    private val loginButton = createActionLink("Log In")
    private val cancelLoginButton = createActionLink("Cancel Login")
    private val logoutButton = createActionLink("Log Out")
    private val copyUrlButton = JButton("Copy URL", AllIcons.Actions.Copy).apply {
        isVisible = false
        toolTipText = "Copy login URL to clipboard"
    }
    private val documentModelCombo = DocumentModelCombo(DocumentModels.OPEN_AI_DEFAULT, vision = true)
    private val accountIdField = JBTextField().apply { isEditable = false }
    private val emailField = JBTextField().apply { isEditable = false }
    private val codexResponseViewer = createResponseViewer()
    private var authUrl: String? = null
    private var authStatusMessage: AuthStatusMessage? = null
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

        loginButton.addActionListener {
            val authService = QuotaAuthService.getInstance()
            loginButton.isEnabled = false
            authStatusMessage = AuthStatusMessage("Opening browser...", false, AuthStatusKind.PENDING)
            updateAuthUi()
            authService.startLoginFlow(
                accountId = boundAccountId.ifBlank { QuotaProviderType.OPEN_AI.id },
                type = QuotaProviderType.OPEN_AI,
                callback = { result ->
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
                        QuotaUsageService.getInstance().refreshAsync(accountKey())
                    }
                }, ModalityState.stateForComponent(modalityComponentProvider() ?: this@OpenAiSettingsPanel))
            }, onAuthUrl = { url ->
                ApplicationManager.getApplication().invokeLater({
                    authUrl = url
                    copyUrlButton.isVisible = true
                    onAuthUrlReceived?.invoke(url)
                }, ModalityState.stateForComponent(modalityComponentProvider() ?: this@OpenAiSettingsPanel))
            })
            updateAuthUi()
        }

        cancelLoginButton.addActionListener {
            val aborted = QuotaAuthService.getInstance().abortLogin(accountKey(), QuotaProviderType.OPEN_AI, "Login canceled")
            authStatusMessage = AuthStatusMessage(
                if (aborted) "Login canceled" else "No login in progress",
                false,
                if (aborted) AuthStatusKind.PENDING else AuthStatusKind.DISCONNECTED
            )
            updateAuthUi()
            onCancelLogin?.invoke()
        }

        logoutButton.addActionListener {
            val cleared = QuotaAuthService.getInstance().clearCredentials(accountKey(), QuotaProviderType.OPEN_AI)
            if (cleared) {
                QuotaUsageService.getInstance().clearUsageData(accountKey(), "Not logged in")
            }
            authStatusMessage = if (cleared) {
                AuthStatusMessage("Logged out", false, AuthStatusKind.DISCONNECTED)
            } else {
                AuthStatusMessage("Could not remove login from Password Safe", true, AuthStatusKind.CONNECTED)
            }
            updateAuthUi()
            updateAccountFields()
            if (cleared) {
                onLogout?.invoke()
            }
        }

        val usageTrackingConfigPanel = panel {
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
            row("Document model:") {
                cell(documentModelCombo.combo).align(AlignX.FILL).resizableColumn().gap(RightGap.SMALL)
                    .comment("Same models as the local proxy. Codex has no usable live model list.")
                cell(documentModelCombo.warning).align(AlignY.TOP)
                cell(DocumentTestButton(de.moritzf.quota.idea.mcp.DocumentToMarkdownProvider.OPEN_AI, { documentModelCombo.selected().orEmpty() }, modalityComponentProvider))
            }
        }

        install(usageTrackingConfigPanel, createResponseSection(codexResponseViewer))
    }

    override fun updateFields() {
        rememberAccount()
        documentModelCombo.show(
            boundAccount?.extra(ProviderAccount.EXTRA_DOCUMENT_MODEL),
            DocumentModels.openAiVisionModels(OpenAiProxyServer.advertisedModels()),
        )
        updateAuthUi()
        updateAccountFields()
    }

    fun documentModelForStorage(): String? = documentModelCombo.storedValue()

    fun documentModelDiffers(saved: String?): Boolean = documentModelCombo.differs(saved)

    override fun updateStatus() {
        updateAuthUi()
        updateAccountFields()
    }

    fun updateAuthUi() {
        val authService = QuotaAuthService.getInstance()
        val loggedIn = authService.isLoggedIn(accountKey(), QuotaProviderType.OPEN_AI)
        val inProgress = authService.isLoginInProgress(accountKey(), QuotaProviderType.OPEN_AI)
        val uiState = QuotaSettingsAuthUiState.create(
            loggedIn, inProgress, authStatusMessage,
            authService.connectionState(accountKey(), QuotaProviderType.OPEN_AI),
        )
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
        accountIdField.text = authService.getAccountId(accountKey(), QuotaProviderType.OPEN_AI).orEmpty()
        emailField.text = if (authService.isLoggedIn(accountKey(), QuotaProviderType.OPEN_AI)) (QuotaUsageService.getInstance().getLastQuota(accountKey()) as? OpenAiCodexQuota)?.email else null.orEmpty()
    }

    override fun updateResponseArea() {
        val json = QuotaUsageService.getInstance().getLastResponseJson(accountKey())
        codexResponseViewer.text = if (json.isNullOrBlank()) "No quota response yet." else json
        codexResponseViewer.setCaretPosition(0)
    }



    private fun createActionLink(text: String): ActionLink {
        return ActionLink(text).apply {
            autoHideOnDisable = false
        }
    }

    private var shownAccountId: String? = null

    private fun accountKey(): String = boundAccountId.ifBlank { QuotaProviderType.OPEN_AI.id }

    private fun rememberAccount() {
        val id = accountKey()
        if (shownAccountId != id) {
            shownAccountId = id
            authStatusMessage = null
            authUrl = null
        }
    }

    private fun copyToClipboard(text: String) {
        val selection = StringSelection(text)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
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
