package de.moritzf.quota.idea.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.opencode.OpenCodeApiKeyStore
import de.moritzf.quota.idea.opencode.OpenCodeSessionCookieStore
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.opencode.OpenCodeQuotaClient
import de.moritzf.quota.opencode.OpenCodeWorkspace
import java.awt.Color
import javax.swing.JComponent

/**
 * OpenCode settings tab.
 */
internal class OpenCodeSettingsPanel(
    private val modalityComponentProvider: () -> JComponent?,
    private val statusLabelDefaultForeground: Color? = null,
) : ProviderSettingsPanel() {
    private val openCodeCookieField = JBPasswordField().apply {
        columns = 40
        toolTipText = "Session cookie from opencode.ai (extract from browser DevTools)"
    }
    private val apiKeyField = JBPasswordField().apply {
        columns = 40
        toolTipText = "OpenCode API key for the local proxy"
    }
    private val openCodeStatusLabel = JBLabel().apply { isVisible = false }
    private val workspaceComboBox = ComboBox<OpenCodeWorkspace>().apply {
        isVisible = false
    }
    private val workspaceLabel = JBLabel("Workspace:").apply { isVisible = false }
    private val workspaceLoadingLabel = JBLabel("Loading workspaces...").apply { isVisible = false }
    private val openCodeJsonViewer = createResponseViewer()
    private var updatingWorkspaceComboBox: Boolean = false
    private var awaitingCookieLoadRefresh: Boolean = false

    init {
        workspaceComboBox.addActionListener {
            if (updatingWorkspaceComboBox) return@addActionListener
            val selected = workspaceComboBox.selectedItem as? OpenCodeWorkspace ?: return@addActionListener
            if (boundAccount?.extra(ProviderAccount.EXTRA_OPENCODE_WORKSPACE) == selected.id) return@addActionListener
            boundAccount?.setExtra(ProviderAccount.EXTRA_OPENCODE_WORKSPACE, selected.id)
        }

        val openCodeConfigPanel = panel {
            row {
                cell(openCodeStatusLabel)
            }
            row {
                text("Extract from opencode.ai → DevTools → Storage → Cookies → \"auth\" cookie value. Valid for 1 year.")
            }
            row("Session cookie:") {
                cell(openCodeCookieField)
                    .resizableColumn()
                    .align(AlignX.FILL)
            }
            row {
                button("Save") {
                    val cookie = String(openCodeCookieField.password)
                    if (cookie.isNotBlank() && cookie != OPENCODE_COOKIE_PLACEHOLDER) {
                        OpenCodeSessionCookieStore.forAccount(accountKey(QuotaProviderType.OPEN_CODE)).save(cookie)
                        openCodeCookieField.text = OPENCODE_COOKIE_PLACEHOLDER
                        setOpenCodePendingStatus("Validating session cookie...")
                        loadWorkspaces(cookie)
                        QuotaUsageService.getInstance().refreshAsync(accountKey(QuotaProviderType.OPEN_CODE))
                    }
                }
                button("Clear") {
                    OpenCodeSessionCookieStore.forAccount(accountKey(QuotaProviderType.OPEN_CODE)).clear()
                    openCodeCookieField.text = ""
                    boundAccount?.setExtra(ProviderAccount.EXTRA_OPENCODE_WORKSPACE, null)
                    workspaceComboBox.removeAllItems()
                    workspaceComboBox.isVisible = false
                    workspaceLabel.isVisible = false
                    workspaceLoadingLabel.isVisible = false
                    updateStatus()
                    QuotaUsageService.getInstance().clearUsageData(accountKey(QuotaProviderType.OPEN_CODE))
                }
            }
            row {
                text("Optional: add an OpenCode API key to expose OpenCode Zen through the local proxy. Quota fetching still uses the session cookie above.")
            }
            row("API key:") {
                cell(apiKeyField)
                    .resizableColumn()
                    .align(AlignX.FILL)
            }
            row {
                button("Save API Key") {
                    saveApiKeyNow()
                }
                button("Clear API Key") {
                    clearApiKeyNow()
                }
            }
            row {
                cell(workspaceLoadingLabel)
            }
            row {
                cell(workspaceLabel).gap(RightGap.SMALL)
                cell(workspaceComboBox)
                    .resizableColumn()
                    .align(AlignX.FILL)
            }
        }

        install(openCodeConfigPanel, createResponseSection(openCodeJsonViewer))
    }

    override fun updateFields() {
        val apiKey = OpenCodeApiKeyStore.forAccount(accountKey(QuotaProviderType.OPEN_CODE)).load(onLoaded = ::refreshAfterApiKeyLoad)
        val cookieStore = OpenCodeSessionCookieStore.forAccount(accountKey(QuotaProviderType.OPEN_CODE))
        val cookie = cookieStore.load(onLoaded = ::refreshAfterCookieLoad)
        apiKeyField.text = if (apiKey.isNullOrBlank()) "" else API_KEY_PLACEHOLDER
        openCodeCookieField.text = if (cookie.isNullOrBlank()) "" else OPENCODE_COOKIE_PLACEHOLDER
        if (!cookie.isNullOrBlank()) {
            loadWorkspaces(cookie)
        } else if (cookieStore.isLoaded()) {
            workspaceComboBox.removeAllItems()
            workspaceComboBox.isVisible = false
            workspaceLabel.isVisible = false
            workspaceLoadingLabel.isVisible = false
        } else {
            workspaceLoadingLabel.text = "Loading session cookie..."
            workspaceLoadingLabel.isVisible = true
            workspaceLabel.isVisible = false
            workspaceComboBox.isVisible = false
        }
        updateStatus()
    }

    override fun updateStatus() {
        val apiKeyStore = OpenCodeApiKeyStore.forAccount(accountKey(QuotaProviderType.OPEN_CODE))
        val cookieStore = OpenCodeSessionCookieStore.forAccount(accountKey(QuotaProviderType.OPEN_CODE))
        val apiKey = apiKeyStore.load(onLoaded = ::refreshAfterApiKeyLoad)
        val cookie = cookieStore.load(onLoaded = ::refreshAfterCookieLoad)
        val openCodeQuota = QuotaUsageService.getInstance().getLastQuota(accountKey(QuotaProviderType.OPEN_CODE)) as? OpenCodeQuota
        val openCodeError = QuotaUsageService.getInstance().getLastError(accountKey(QuotaProviderType.OPEN_CODE))

        when {
            !apiKeyStore.isLoaded() || !cookieStore.isLoaded() -> {
                openCodeStatusLabel.text = formatStatusText("Loading OpenCode credentials...", AuthStatusKind.PENDING)
                openCodeStatusLabel.foreground = statusLabelDefaultForeground ?: openCodeStatusLabel.foreground
            }
            cookie == null && apiKey.isNullOrBlank() -> {
                openCodeStatusLabel.text = formatStatusText("No session cookie configured for quota; no API key configured for proxy", AuthStatusKind.DISCONNECTED)
                openCodeStatusLabel.foreground = statusLabelDefaultForeground ?: openCodeStatusLabel.foreground
            }
            cookie == null -> {
                openCodeStatusLabel.text = formatStatusText("API key stored for proxy; no session cookie configured for quota", AuthStatusKind.CONNECTED)
                openCodeStatusLabel.foreground = statusLabelDefaultForeground ?: openCodeStatusLabel.foreground
            }
            workspaceComboBox.isVisible && workspaceComboBox.itemCount > 0 -> {
                openCodeStatusLabel.text = formatStatusText("Connected", AuthStatusKind.CONNECTED)
                openCodeStatusLabel.foreground = statusLabelDefaultForeground ?: openCodeStatusLabel.foreground
            }
            openCodeError != null -> {
                openCodeStatusLabel.text = formatStatusText("Error: $openCodeError", AuthStatusKind.DISCONNECTED)
                openCodeStatusLabel.foreground = statusLabelDefaultForeground ?: openCodeStatusLabel.foreground
            }
            openCodeQuota != null -> {
                openCodeStatusLabel.text = formatStatusText("Connected", AuthStatusKind.CONNECTED)
                openCodeStatusLabel.foreground = statusLabelDefaultForeground ?: openCodeStatusLabel.foreground
            }
            else -> {
                openCodeStatusLabel.text = formatStatusText("Session cookie stored securely", AuthStatusKind.CONNECTED)
                openCodeStatusLabel.foreground = statusLabelDefaultForeground ?: openCodeStatusLabel.foreground
            }
        }
        openCodeStatusLabel.isVisible = true
    }

    private fun setOpenCodePendingStatus(text: String) {
        openCodeStatusLabel.text = formatStatusText(text, AuthStatusKind.PENDING)
        openCodeStatusLabel.foreground = statusLabelDefaultForeground ?: openCodeStatusLabel.foreground
        openCodeStatusLabel.isVisible = true
    }

    private fun saveApiKeyNow() {
        val apiKey = String(apiKeyField.password).trim()
        if (apiKey.isNotBlank() && apiKey != API_KEY_PLACEHOLDER) {
            OpenCodeApiKeyStore.forAccount(accountKey(QuotaProviderType.OPEN_CODE)).save(apiKey)
            apiKeyField.text = API_KEY_PLACEHOLDER
            updateStatus()
        }
    }

    private fun clearApiKeyNow() {
        OpenCodeApiKeyStore.forAccount(accountKey(QuotaProviderType.OPEN_CODE)).clear()
        apiKeyField.text = ""
        updateStatus()
    }

    override fun updateResponseArea() {
        val quota = QuotaUsageService.getInstance().getLastQuota(accountKey(QuotaProviderType.OPEN_CODE)) as? OpenCodeQuota
        val error = QuotaUsageService.getInstance().getLastError(accountKey(QuotaProviderType.OPEN_CODE))
        val rawJson = QuotaUsageService.getInstance().getLastResponseJson(accountKey(QuotaProviderType.OPEN_CODE))

        openCodeJsonViewer.text = when {
            error != null && !rawJson.isNullOrBlank() -> "Error: $error\n\n$rawJson"
            error != null -> "Error: $error"
            quota == null -> "No OpenCode response yet."
            !rawJson.isNullOrBlank() -> rawJson
            else -> {
                try {
                    de.moritzf.quota.shared.JsonSupport.json.encodeToString(
                        OpenCodeQuota.serializer(),
                        quota,
                    )
                } catch (exception: Exception) {
                    "Could not serialize response: ${exception.message}"
                }
            }
        }
        openCodeJsonViewer.setCaretPosition(0)
    }

    private fun loadWorkspaces(cookie: String) {
        workspaceLoadingLabel.text = "Loading workspaces..."
        workspaceLoadingLabel.isVisible = true
        workspaceLabel.isVisible = false
        workspaceComboBox.isVisible = false

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = OpenCodeQuotaClient()
                val workspaces = client.fetchWorkspaces(cookie)

                ApplicationManager.getApplication().invokeLater({
                    workspaceComboBox.removeAllItems()
                    workspaces.forEach { workspaceComboBox.addItem(it) }

                    val accountId = accountKey(QuotaProviderType.OPEN_CODE)
                    val storedId = boundAccount?.extra(ProviderAccount.EXTRA_OPENCODE_WORKSPACE)
                        ?: QuotaSettingsState.getInstance().openCodeWorkspaceIdFor(accountId)
                    val preselected = workspaces.find { it.id == storedId }
                        ?: workspaces.firstOrNull { it.mine && it.hasGoSubscription }
                        ?: workspaces.firstOrNull { it.hasGoSubscription }
                        ?: workspaces.firstOrNull()

                    updatingWorkspaceComboBox = true
                    try {
                        preselected?.let { workspace ->
                            workspaceComboBox.selectedItem = workspace
                            boundAccount?.setExtra(ProviderAccount.EXTRA_OPENCODE_WORKSPACE, workspace.id)
                        }
                    } finally {
                        updatingWorkspaceComboBox = false
                    }

                    workspaceLoadingLabel.isVisible = false
                    workspaceLabel.isVisible = true
                    workspaceComboBox.isVisible = true
                    updateStatus()
                }, ModalityState.stateForComponent(modalityComponentProvider() ?: this@OpenCodeSettingsPanel))
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater({
                    workspaceComboBox.removeAllItems()
                    workspaceComboBox.isVisible = false
                    workspaceLabel.isVisible = false
                    workspaceLoadingLabel.text = "Could not load workspaces: ${e.message}"
                    workspaceLoadingLabel.isVisible = true
                    updateStatus()
                }, ModalityState.stateForComponent(modalityComponentProvider() ?: this@OpenCodeSettingsPanel))
            }
        }
    }

    private fun refreshAfterCookieLoad() {
        if (awaitingCookieLoadRefresh) {
            return
        }
        awaitingCookieLoadRefresh = true
        ApplicationManager.getApplication().invokeLater({
            awaitingCookieLoadRefresh = false
            updateFields()
        }, ModalityState.stateForComponent(modalityComponentProvider() ?: this))
    }

    fun selectedWorkspaceId(): String? = (workspaceComboBox.selectedItem as? OpenCodeWorkspace)?.id

    private fun refreshAfterApiKeyLoad() {
        updateFields()
        updateResponseArea()
    }



    private fun formatStatusText(text: String, kind: AuthStatusKind): String {
        val color = when (kind) {
            AuthStatusKind.CONNECTED -> "#4CAF50"
            AuthStatusKind.DISCONNECTED -> "#F44336"
            AuthStatusKind.PENDING -> "#FFC107"
        }
        return "<html><span style=\"color: $color\">●</span>&nbsp;${QuotaUiUtil.escapeHtml(text)}</html>"
    }

    private companion object {
        private const val OPENCODE_COOKIE_PLACEHOLDER = "********"
        private const val API_KEY_PLACEHOLDER = "********"
    }
}
