package de.moritzf.quota.idea.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import de.moritzf.quota.idea.common.IdeProxyFactories
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.mcp.DocumentToMarkdownProvider
import de.moritzf.quota.github.GitHubQuota
import de.moritzf.quota.idea.auth.AuthService
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.github.GitHubAuthService
import de.moritzf.quota.idea.github.GitHubCredentialsStore
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.github.GitHubQuotaClient
import de.moritzf.quota.github.proxy.fetchGitHubListedModels
import de.moritzf.quota.github.proxy.githubDocumentModelIds
import java.awt.Color
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JComponent

internal class GitHubSettingsPanel(
    private val modalityComponentProvider: () -> JComponent?,
    private val statusLabelDefaultForeground: Color? = null,
) : ProviderSettingsPanel() {
    val enterpriseHostField = JBTextField().apply {
        emptyText.text = "github.com or github.example.com"
        columns = 36
    }
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
    private val documentModelCombo = DocumentModelCombo("Select a model", vision = true)
    private val testDocumentButton = DocumentTestButton(DocumentToMarkdownProvider.GITHUB, { documentModelCombo.storedValue().orEmpty() }, modalityComponentProvider)
    private val responseViewer = createResponseViewer()
    private var modelRefreshGeneration = 0
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
            val authService = GitHubAuthService.forAccount(accountKey(QuotaProviderType.GITHUB))
            if (authService.isLoggedIn()) {
                updateStatus()
                return@addActionListener
            }
            val host = GitHubQuotaClient.normalizedEnterpriseHost(enterpriseHostField.text)
            boundAccount?.setExtra(ProviderAccount.EXTRA_GITHUB_HOST, host.takeUnless { it == "github.com" })
            loginButton.isEnabled = false
            authStatusMessage = AuthStatusMessage("Requesting device code...", false, AuthStatusKind.PENDING)
            updateStatus()
            authService.startLoginFlow(
                callback = { result ->
                ApplicationManager.getApplication().invokeLater({
                    authStatusMessage = if (result.success) {
                        AuthStatusMessage("Connected", false, AuthStatusKind.CONNECTED)
                    } else {
                        AuthStatusMessage(result.message ?: "Login failed", true, AuthStatusKind.DISCONNECTED)
                    }
                    loginButton.isEnabled = true
                    updateStatus()
                    if (result.success) {
                        QuotaUsageService.getInstance().refreshAsync(accountKey(QuotaProviderType.GITHUB))
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
                    updateStatus()
                }, ModalityState.stateForComponent(modalityComponentProvider() ?: this))
            }, enterpriseHost = host)
            updateStatus()
        }

        cancelLoginButton.addActionListener {
            val aborted = (GitHubAuthService.forAccount(accountKey(QuotaProviderType.GITHUB)) as AuthService).abortLogin("Login canceled")
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
                (GitHubAuthService.forAccount(accountKey(QuotaProviderType.GITHUB)) as AuthService).clearCredentials()
                ApplicationManager.getApplication().invokeLater({
                    authStatusMessage = AuthStatusMessage("Logged out", false, AuthStatusKind.DISCONNECTED)
                    QuotaUsageService.getInstance().clearUsageData(accountKey(QuotaProviderType.GITHUB), "Not logged in")
                    updateStatus()
                }, ModalityState.stateForComponent(modalityComponentProvider() ?: this))
            }
        }

        install(panel {
            row("Enterprise host:") { cell(enterpriseHostField).resizableColumn().align(AlignX.FILL) }
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
            row("Document model:") {
                cell(documentModelCombo.combo).align(AlignX.FILL).resizableColumn()
                    .comment("Copilot models that advertise application/pdf. If Copilot does not say, every model is listed.")
                cell(documentModelCombo.warning).align(com.intellij.ui.dsl.builder.AlignY.TOP)
                cell(testDocumentButton)
            }
        }, createResponseSection(responseViewer))
    }

    fun documentModelForStorage(): String? = documentModelCombo.storedValue()
    fun documentModelDiffers(saved: String?): Boolean = documentModelCombo.differs(saved)

    override fun updateFields() {
        rememberAccount()
        GitHubCredentialsStore.forAccount(accountKey(QuotaProviderType.GITHUB)).load(onLoaded = ::refreshAfterCredentialsLoad)
        val settings = QuotaSettingsState.getInstance()
        val accountId = accountKey(QuotaProviderType.GITHUB)
        enterpriseHostField.text = boundAccount?.extra(ProviderAccount.EXTRA_GITHUB_HOST)
            ?: settings.githubHostFor(accountId)
        updateStatus()
        refreshDocumentModels()
    }

    private fun refreshDocumentModels() {
        val generation = ++modelRefreshGeneration
        val accountId = accountKey(QuotaProviderType.GITHUB)
        val saved = boundAccount?.extra(ProviderAccount.EXTRA_DOCUMENT_MODEL)
        ApplicationManager.getApplication().executeOnPooledThread {
            val token = GitHubCredentialsStore.forAccount(accountId).loadBlocking()?.accessToken
            val discovered = if (token.isNullOrBlank()) emptyList() else runCatching {
                val base = IdeProxyFactories.githubCopilotBaseUri(QuotaSettingsState.getInstance().githubHostFor(accountId))
                githubDocumentModelIds(fetchGitHubListedModels(base, token))
            }.getOrDefault(emptyList())
            ApplicationManager.getApplication().invokeLater({
                if (generation != modelRefreshGeneration) return@invokeLater
                documentModelCombo.show(saved, (discovered + listOfNotNull(saved)).distinct())
            }, ModalityState.stateForComponent(modalityComponentProvider() ?: this))
        }
    }

    override fun updateStatus() {
        val store = GitHubCredentialsStore.forAccount(accountKey(QuotaProviderType.GITHUB))
        val credentials = store.load(onLoaded = ::refreshAfterCredentialsLoad)
        val inProgress = (GitHubAuthService.forAccount(accountKey(QuotaProviderType.GITHUB)) as AuthService).isLoginInProgress()
        val quota = QuotaUsageService.getInstance().getLastQuota(accountKey(QuotaProviderType.GITHUB)) as? GitHubQuota
        val error = QuotaUsageService.getInstance().getLastError(accountKey(QuotaProviderType.GITHUB))
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

    override fun updateResponseArea() {
        val raw = QuotaUsageService.getInstance().getLastResponseJson(accountKey(QuotaProviderType.GITHUB))
        val error = QuotaUsageService.getInstance().getLastError(accountKey(QuotaProviderType.GITHUB))
        responseViewer.text = when {
            error != null && !raw.isNullOrBlank() -> "Error: $error\n\n$raw"
            error != null -> "Error: $error"
            raw.isNullOrBlank() -> "No GitHub response yet."
            else -> raw
        }
        responseViewer.setCaretPosition(0)
    }

    private var shownAccountId: String? = null

    private fun rememberAccount() {
        val id = accountKey(QuotaProviderType.GITHUB)
        if (shownAccountId != id) {
            shownAccountId = id
            authStatusMessage = null
        }
    }

    private fun refreshAfterCredentialsLoad() {
        updateFields()
        updateResponseArea()
    }

    fun normalizedEnterpriseHostForStorage(): String {
        val normalized = GitHubQuotaClient.normalizedEnterpriseHost(enterpriseHostField.text)
        return if (normalized == "github.com") "" else normalized
    }

    private fun setPending(text: String) {
        authStatusMessage = AuthStatusMessage(text, false, AuthStatusKind.PENDING)
        statusLabel.text = formatStatusText(text, AuthStatusKind.PENDING)
        statusLabel.isVisible = true
    }

    private fun copyToClipboard(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
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
