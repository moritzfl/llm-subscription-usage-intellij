package de.moritzf.quota.idea.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.DocumentModels
import de.moritzf.quota.supergrok.SuperGrokQuota
import de.moritzf.quota.supergrok.proxy.SuperGrokSubscriptionProxyProvider
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.awt.Color
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JComponent

/** SuperGrok settings tab backed by plugin-managed xAI OAuth credentials. */
internal class SuperGrokSettingsPanel(
    private val modalityComponentProvider: () -> JComponent?,
    private val statusLabelDefaultForeground: Color? = null,
) : ProviderSettingsPanel() {
    private val documentModelCombo = DocumentModelCombo(DocumentModels.SUPERGROK_DEFAULT, vision = true)
    private val statusLabel = JBLabel().apply { isVisible = false }
    private var modelRefreshGeneration = 0
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
            loginButton.isEnabled = false
            authStatusMessage = AuthStatusMessage("Opening browser...", false, AuthStatusKind.PENDING)
            updateAuthUi()
            authService.startLoginFlow(
                accountId = accountId(),
                type = QuotaProviderType.SUPERGROK,
                callback = { result ->
                ApplicationManager.getApplication().invokeLater({
                    authStatusMessage = if (result.success) {
                        AuthStatusMessage("Connected to xAI/Grok", false, AuthStatusKind.CONNECTED)
                    } else {
                        AuthStatusMessage(result.message ?: "Login failed", true, AuthStatusKind.DISCONNECTED)
                    }
                    loginButton.isEnabled = true
                    updateAuthUi()
                    if (result.success) {
                        QuotaUsageService.getInstance().refreshAsync(accountId())
                    }
                }, ModalityState.stateForComponent(modalityComponentProvider() ?: this@SuperGrokSettingsPanel))
            }, onAuthUrl = { url ->
                ApplicationManager.getApplication().invokeLater({
                    authUrl = url
                    copyUrlButton.isVisible = true
                }, ModalityState.stateForComponent(modalityComponentProvider() ?: this@SuperGrokSettingsPanel))
            })
            updateAuthUi()
        }

        cancelLoginButton.addActionListener {
            val aborted = QuotaAuthService.getInstance().abortLogin(accountId(), QuotaProviderType.SUPERGROK, "Login canceled")
            authStatusMessage = AuthStatusMessage(
                if (aborted) "Login canceled" else "No login in progress",
                false,
                if (aborted) AuthStatusKind.PENDING else AuthStatusKind.DISCONNECTED,
            )
            updateAuthUi()
        }

        logoutButton.addActionListener {
            val cleared = QuotaAuthService.getInstance().clearCredentials(accountId(), QuotaProviderType.SUPERGROK)
            if (cleared) {
                QuotaUsageService.getInstance().clearUsageData(accountId())
            }
            authStatusMessage = if (cleared) {
                AuthStatusMessage("Logged out of xAI/Grok", false, AuthStatusKind.DISCONNECTED)
            } else {
                AuthStatusMessage("Could not remove xAI/Grok login from Password Safe", true, AuthStatusKind.CONNECTED)
            }
            updateAuthUi()
        }

        val configPanel = panel {
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
                text("Uses plugin-managed xAI OAuth with the Grok CLI billing API. No local Grok CLI auth file is required.")
            }
            row("Document model:") {
                cell(documentModelCombo.combo).align(AlignX.FILL).resizableColumn().gap(RightGap.SMALL)
                    .comment("Loaded from the xAI model list. Image models are left out.")
                cell(documentModelCombo.warning).align(AlignY.TOP)
                cell(DocumentTestButton(de.moritzf.quota.idea.mcp.DocumentToMarkdownProvider.SUPERGROK, { documentModelCombo.selected().orEmpty() }, modalityComponentProvider))
            }
        }

        install(configPanel, createResponseSection(jsonViewer))
    }

    override fun updateFields() {
        rememberAccount()
        showDocumentModels(emptyList())
        updateAuthUi()
        updateResponseArea()
        refreshDocumentModels()
    }

    fun documentModelForStorage(): String? = documentModelCombo.storedValue()

    fun documentModelDiffers(saved: String?): Boolean = documentModelCombo.differs(saved)

    private fun showDocumentModels(discovered: List<String>, selection: String? = boundAccount?.extra(ProviderAccount.EXTRA_DOCUMENT_MODEL)) {
        documentModelCombo.show(selection, DocumentModels.superGrokChoices(discovered, selection))
    }

    private fun refreshDocumentModels() {
        val accountId = accountId()
        val generation = ++modelRefreshGeneration
        ApplicationManager.getApplication().executeOnPooledThread {
            val token = QuotaAuthService.getInstance().getAccessTokenBlocking(accountId, QuotaProviderType.SUPERGROK)
            val discovered = if (token.isNullOrBlank()) emptyList() else fetchSuperGrokModelIds(token)
            ApplicationManager.getApplication().invokeLater({
                if (generation != modelRefreshGeneration || accountId() != accountId) return@invokeLater
                showDocumentModels(discovered, documentModelCombo.selected() ?: boundAccount?.extra(ProviderAccount.EXTRA_DOCUMENT_MODEL))
            }, ModalityState.stateForComponent(modalityComponentProvider() ?: this))
        }
    }

    override fun updateStatus() {
        updateAuthUi()
    }

    private fun updateAuthUi() {
        val authService = QuotaAuthService.getInstance()
        val loggedIn = authService.isLoggedIn(accountId(), QuotaProviderType.SUPERGROK)
        val inProgress = authService.isLoginInProgress(accountId(), QuotaProviderType.SUPERGROK)
        val error = QuotaUsageService.getInstance().getLastError(accountId())
        val uiState = QuotaSettingsAuthUiState.create(
            loggedIn, inProgress, authStatusMessage,
            authService.connectionState(accountId(), QuotaProviderType.SUPERGROK), error,
        )
        loginButton.isEnabled = uiState.loginEnabled
        cancelLoginButton.isEnabled = uiState.cancelEnabled
        logoutButton.isEnabled = uiState.logoutEnabled
        val status = requireNotNull(uiState.visibleStatusMessage)
        statusLabel.text = formatStatusText(status.text, status.kind)
        statusLabel.foreground = statusLabelDefaultForeground ?: statusLabel.foreground
        statusLabel.isVisible = true
        if (!inProgress) {
            copyUrlButton.isVisible = false
            authUrl = null
        }
    }

    override fun updateResponseArea() {
        val quota = QuotaUsageService.getInstance().getLastQuota(accountId()) as? SuperGrokQuota
        val error = QuotaUsageService.getInstance().getLastError(accountId())
        val rawJson = QuotaUsageService.getInstance().getLastResponseJson(accountId())
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



    private var shownAccountId: String? = null

    private fun accountId(): String = accountKey(QuotaProviderType.SUPERGROK)

    private fun rememberAccount() {
        val id = accountId()
        if (shownAccountId != id) {
            shownAccountId = id
            authStatusMessage = null
            authUrl = null
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

private fun fetchSuperGrokModelIds(token: String): List<String> {
    val request = HttpRequest.newBuilder(URI.create("${SuperGrokSubscriptionProxyProvider.DEFAULT_UPSTREAM_BASE_URI}/models"))
        .timeout(Duration.ofSeconds(30))
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/json")
        .header("User-Agent", "openai-usage-quota-intellij")
        .GET()
        .build()
    val response = runCatching {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
            .send(request, HttpResponse.BodyHandlers.ofString())
    }.getOrNull() ?: return emptyList()
    if (response.statusCode() !in 200..299) return emptyList()
    return DocumentModels.parseSuperGrokDocumentModelIds(response.body())
}
