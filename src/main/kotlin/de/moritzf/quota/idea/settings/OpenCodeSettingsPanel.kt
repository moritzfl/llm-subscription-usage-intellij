package de.moritzf.quota.idea.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import de.moritzf.quota.idea.auth.OAuthCredentials
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.mcp.DocumentToMarkdownProvider
import de.moritzf.quota.idea.opencode.OpenCodeAuthService
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.proxy.logging.RequestLogger
import de.moritzf.quota.opencode.proxy.OpenCodeConsoleProxy
import de.moritzf.quota.opencode.proxy.OpenCodeConsoleSession
import de.moritzf.quota.opencode.OpenCodeWorkspace
import de.moritzf.quota.shared.DocumentModels
import de.moritzf.quota.opencode.OpenCodeQuota
import java.awt.Color
import java.net.http.HttpClient
import java.nio.file.Path
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JComponent

internal class OpenCodeSettingsPanel(
    private val modalityComponentProvider: () -> JComponent?,
    private val statusLabelDefaultForeground: Color? = null,
) : ProviderSettingsPanel() {
    private val statusLabel = JBLabel()
    private val loginButton = ActionLink("Sign in to OpenCode").apply { autoHideOnDisable = false }
    private val cancelButton = ActionLink("Cancel Login").apply { autoHideOnDisable = false }
    private val logoutButton = ActionLink("Log Out").apply { autoHideOnDisable = false }
    private val copyUrlButton = JButton("Copy URL", AllIcons.Actions.Copy).apply { isVisible = false }
    private val userCodeLabel = JBLabel().apply { isVisible = false }
    private val workspaceComboBox = ComboBox<OpenCodeWorkspace>()
    private val workspaceStatus = JBLabel()
    private val documentModelCombo = DocumentModelCombo(DocumentModels.OFF, vision = true)
    private val testDocumentButton = DocumentTestButton(
        DocumentToMarkdownProvider.OPEN_CODE,
        { documentModelCombo.storedValue().orEmpty() },
        modalityComponentProvider,
    )
    private val responseViewer = createResponseViewer()
    private var modelRefreshGeneration = 0
    private var verificationUrl: String? = null
    private var authMessage: AuthStatusMessage? = null
    private var shownAccountId: String? = null
    private var workspaceCredentials: OAuthCredentials? = null
    private var workspaceRequest = 0L
    private var updatingWorkspaces = false
    private var loggingOut = false
    private var uiGeneration = 0L

    private fun accountId() = accountKey(QuotaProviderType.OPEN_CODE)
    private fun auth() = OpenCodeAuthService.getInstance()

    init {
        copyUrlButton.addActionListener {
            verificationUrl?.let { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(it), null) }
        }
        loginButton.addActionListener {
            val id = accountId()
            val generation = ++uiGeneration
            authMessage = AuthStatusMessage("Opening browser...", false, AuthStatusKind.PENDING)
            auth().startLoginFlow(id, callback = { result ->
                if (result.success) QuotaUsageService.getInstance().refreshAsync(id)
                onUi(id) {
                    if (uiGeneration != generation) return@onUi
                    authMessage = if (result.success) null
                    else AuthStatusMessage(result.message ?: "Login failed", true, AuthStatusKind.DISCONNECTED)
                    workspaceCredentials = null
                    updateFields()
                }
            }, onVerificationUrl = { url, code ->
                onUi(id) {
                    if (uiGeneration != generation) return@onUi
                    verificationUrl = url
                    copyUrlButton.isVisible = true
                    userCodeLabel.text = "OpenCode code: ${QuotaUiUtil.escapeHtml(code)}"
                    userCodeLabel.isVisible = code.isNotBlank()
                    authMessage = AuthStatusMessage("Waiting for browser authorization...", false, AuthStatusKind.PENDING)
                    updateStatus()
                }
            })
            updateStatus()
        }
        cancelButton.addActionListener {
            uiGeneration++
            auth().abortLogin(accountId())
            authMessage = AuthStatusMessage("Login canceled", false, AuthStatusKind.DISCONNECTED)
            updateStatus()
        }
        logoutButton.addActionListener {
            val id = accountId()
            uiGeneration++
            workspaceRequest++
            loggingOut = true
            updateStatus()
            ApplicationManager.getApplication().executeOnPooledThread {
                val result = runCatching { auth().clearCredentials(id) }
                if (result.isSuccess) QuotaUsageService.getInstance().clearUsageData(id, "Not signed in to OpenCode")
                onUi(id) {
                    loggingOut = false
                    authMessage = result.exceptionOrNull()?.let {
                        AuthStatusMessage(it.message ?: "Could not log out", true, AuthStatusKind.DISCONNECTED)
                    }
                    if (result.isSuccess) boundAccount?.setExtra(ProviderAccount.EXTRA_OPENCODE_WORKSPACE, null)
                    workspaceCredentials = null
                    updateFields()
                }
            }
        }
        workspaceComboBox.addActionListener {
            if (!updatingWorkspaces) {
                val selected = workspaceComboBox.selectedItem as? OpenCodeWorkspace
                if (selected != null) boundAccount?.setExtra(ProviderAccount.EXTRA_OPENCODE_WORKSPACE, selected.id)
            }
        }
        install(panel {
            row { cell(statusLabel).gap(RightGap.SMALL); cell(copyUrlButton) }
            row { cell(userCodeLabel) }
            row {
                cell(loginButton).gap(RightGap.SMALL)
                cell(cancelButton).gap(RightGap.SMALL)
                cell(logoutButton)
            }
            row { text("Sign in through OpenCode Console for Go quotas, Zen balance, and the local Zen proxy. Select the organization in your browser.") }
            row("Organization:") { cell(workspaceComboBox).resizableColumn().align(AlignX.FILL) }
            row { cell(workspaceStatus) }
            row { text("To change a browser-scoped organization, sign in again.") }
            row("Document model:") {
                cell(documentModelCombo.combo).align(AlignX.FILL).resizableColumn()
                    .comment(DocumentModels.OFF_COMMENT)
                cell(documentModelCombo.warning).align(com.intellij.ui.dsl.builder.AlignY.TOP)
                cell(testDocumentButton)
            }
        }, createResponseSection(responseViewer))
    }

    fun documentModelForStorage(): String? = documentModelCombo.storedValue()
    fun documentModelDiffers(saved: String?): Boolean = documentModelCombo.differs(saved)

    override fun updateFields() {
        val id = accountId()
        if (shownAccountId != id) {
            shownAccountId = id
            uiGeneration++
            workspaceRequest++
            workspaceCredentials = null
            authMessage = null
            verificationUrl = null
            loggingOut = false
            replaceWorkspaces(emptyList(), null)
        }
        val credentials = auth().load(id) { onUi(id) { updateFields() } }
        if (credentials != null && credentials !== workspaceCredentials && !loggingOut) {
            workspaceCredentials = credentials
            loadWorkspaces(id)
        } else if (credentials == null && auth().isLoaded(id)) {
            workspaceRequest++
            workspaceCredentials = null
            replaceWorkspaces(emptyList(), null)
            workspaceStatus.text = ""
        }
        updateStatus()
        refreshDocumentModels()
    }

    private fun refreshDocumentModels() {
        val generation = ++modelRefreshGeneration
        val id = accountId()
        val saved = boundAccount?.extra(ProviderAccount.EXTRA_DOCUMENT_MODEL)
        ApplicationManager.getApplication().executeOnPooledThread {
            val discovered = runCatching {
                val auth = auth()
                val credentials = auth.credentials(id) ?: return@runCatching emptyList()
                val token = credentials.accessToken ?: return@runCatching emptyList()
                val organization = credentials.accountId ?: QuotaSettingsState.getInstance().openCodeWorkspaceIdFor(id)
                val session = OpenCodeConsoleSession(id, token, organization) { auth.credentials(id, it)?.accessToken }
                val models = OpenCodeConsoleProxy(HttpClient.newHttpClient(), RequestLogger(false, Path.of("logs"))).models(session)
                de.moritzf.quota.opencode.proxy.OpenCodeConsoleModel.documentModelIds(models)
            }.getOrDefault(emptyList())
            ApplicationManager.getApplication().invokeLater({
                if (generation != modelRefreshGeneration) return@invokeLater
                documentModelCombo.show(saved, (discovered + listOfNotNull(saved)).distinct())
            }, ModalityState.stateForComponent(modalityComponentProvider() ?: this))
        }
    }

    override fun updateStatus() {
        val id = accountId()
        val credentials = auth().load(id) { onUi(id) { updateFields() } }
        val inProgress = auth().isLoginInProgress(id)
        if (credentials != null && credentials !== workspaceCredentials && !loggingOut) {
            workspaceCredentials = credentials
            loadWorkspaces(id)
        }
        val error = QuotaUsageService.getInstance().getLastError(id)
        val warnings = (QuotaUsageService.getInstance().getLastQuota(id) as? OpenCodeQuota)?.warnings.orEmpty()
        val message = when {
            loggingOut -> AuthStatusMessage("Logging out...", false, AuthStatusKind.PENDING)
            authMessage != null -> authMessage!!
            !auth().isLoaded(id) -> AuthStatusMessage("Loading credentials...", false, AuthStatusKind.PENDING)
            auth().loadError(id) != null -> AuthStatusMessage(auth().loadError(id)!!, true, AuthStatusKind.DISCONNECTED)
            credentials == null -> AuthStatusMessage("Not signed in to OpenCode", false, AuthStatusKind.DISCONNECTED)
            error != null -> AuthStatusMessage(error, true, AuthStatusKind.DISCONNECTED)
            warnings.isNotEmpty() -> AuthStatusMessage("Connected; ${warnings.joinToString("; ")}", false, AuthStatusKind.PENDING)
            else -> AuthStatusMessage("Connected", false, AuthStatusKind.CONNECTED)
        }
        val color = when (message.kind) {
            AuthStatusKind.CONNECTED -> "#4CAF50"
            AuthStatusKind.DISCONNECTED -> "#F44336"
            AuthStatusKind.PENDING -> "#FFC107"
        }
        statusLabel.text = "<html><span style=\"color: $color\">●</span>&nbsp;${QuotaUiUtil.escapeHtml(message.text)}</html>"
        statusLabel.foreground = statusLabelDefaultForeground ?: statusLabel.foreground
        loginButton.isEnabled = !inProgress && !loggingOut
        cancelButton.isEnabled = inProgress && !loggingOut
        logoutButton.isEnabled = credentials != null && !inProgress && !loggingOut
        if (!inProgress) {
            verificationUrl = null
            copyUrlButton.isVisible = false
            userCodeLabel.isVisible = false
        }
    }

    private fun loadWorkspaces(id: String) {
        val request = ++workspaceRequest
        val scoped = auth().load(id)?.accountId?.let(::OpenCodeWorkspace)
        replaceWorkspaces(listOfNotNull(scoped), scoped)
        workspaceStatus.text = "Loading organizations..."
        workspaceComboBox.isEnabled = false
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { auth().workspaces(id) }
            onUi(id) {
                if (request != workspaceRequest) return@onUi
                result.fold(onSuccess = { workspaces ->
                    val selected = workspaces.find { it.id == selectedWorkspaceId() } ?: workspaces.firstOrNull()
                    replaceWorkspaces(workspaces, selected)
                    selected?.let { boundAccount?.setExtra(ProviderAccount.EXTRA_OPENCODE_WORKSPACE, it.id) }
                    workspaceStatus.text = if (workspaces.isEmpty()) "No organizations found" else ""
                }, onFailure = {
                    workspaceStatus.text = "Could not load organizations: ${it.message}"
                })
            }
        }
    }

    private fun replaceWorkspaces(workspaces: List<OpenCodeWorkspace>, selected: OpenCodeWorkspace?) {
        updatingWorkspaces = true
        try {
            workspaceComboBox.removeAllItems()
            workspaces.forEach(workspaceComboBox::addItem)
            workspaceComboBox.selectedItem = selected
            workspaceComboBox.isEnabled = workspaces.size > 1
        } finally {
            updatingWorkspaces = false
        }
    }

    fun selectedWorkspaceId(): String? = auth().load(accountId())?.accountId
        ?: (workspaceComboBox.selectedItem as? OpenCodeWorkspace)?.id
        ?: boundAccount?.extra(ProviderAccount.EXTRA_OPENCODE_WORKSPACE)

    override fun updateResponseArea() {
        val service = QuotaUsageService.getInstance()
        val raw = service.getLastResponseJson(accountId())
        val error = service.getLastError(accountId())
        responseViewer.text = when {
            error != null && !raw.isNullOrBlank() -> "Error: $error\n\n$raw"
            error != null -> "Error: $error"
            raw.isNullOrBlank() -> "No OpenCode response yet."
            else -> raw
        }
        responseViewer.setCaretPosition(0)
    }

    private fun onUi(id: String, action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({
            if (accountId() == id) action()
        }, ModalityState.stateForComponent(modalityComponentProvider() ?: this))
    }
}
