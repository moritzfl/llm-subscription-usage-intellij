package de.moritzf.quota.idea.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.mistral.MistralApiKeyStore
import de.moritzf.quota.idea.mistral.MistralSessionCookieStore
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.mistral.MistralOcrClient
import de.moritzf.quota.mistral.MistralQuota
import de.moritzf.quota.mistral.MistralQuotaClient
import de.moritzf.quota.mistral.proxy.MistralSubscriptionProxyProvider
import de.moritzf.quota.shared.DocumentModels
import java.net.URI
import java.awt.Color
import javax.swing.JComponent

internal class MistralSettingsPanel(
    private val modalityComponentProvider: () -> JComponent?,
    private val statusLabelDefaultForeground: Color? = null,
) : ProviderSettingsPanel() {
    private val sessionNameField = JBTextField().apply {
        columns = 40
        emptyText.text = "ory_session_…"
        toolTipText = "Cookie name from admin.mistral.ai, starts with ory_session_"
    }
    private val sessionValueField = JBPasswordField().apply {
        columns = 40
        toolTipText = "Value of the ory_session_* cookie"
    }
    private val csrfField = JBPasswordField().apply {
        columns = 40
        toolTipText = "Value of the csrftoken cookie from console.mistral.ai"
    }
    private val apiKeyField = JBPasswordField().apply {
        columns = 40
        toolTipText = "Mistral API key for MCP search, images, and OCR"
    }
    private val documentModelCombo = DocumentModelCombo(MistralOcrClient.DEFAULT_MODEL, vision = false)
    private var modelRefreshGeneration = 0
    private val statusLabel = JBLabel().apply { isVisible = false }
    private val responseViewer = createResponseViewer()

    init {
        install(panel {
            row { cell(statusLabel) }
            row {
                text("Quota cookies from admin.mistral.ai / console.mistral.ai → DevTools → Application → Cookies. Copy each name/value.")
            }
            row("ory_session name:") { cell(sessionNameField).resizableColumn().align(AlignX.FILL) }
            row("ory_session value:") { cell(sessionValueField).resizableColumn().align(AlignX.FILL) }
            row("csrftoken:") { cell(csrfField).resizableColumn().align(AlignX.FILL) }
            row("API key:") { cell(apiKeyField).resizableColumn().align(AlignX.FILL) }
            row("Document model:") {
                cell(documentModelCombo.combo).align(AlignX.FILL).resizableColumn()
                    .comment("- turns conversion off.")
                cell(DocumentTestButton(de.moritzf.quota.idea.mcp.DocumentToMarkdownProvider.MISTRAL, { documentModelCombo.selected().orEmpty() }, modalityComponentProvider))
            }
            row {
                button("Save") { saveNow() }
                button("Clear") { clearNow() }
            }
        }, createResponseSection(responseViewer, "Last quota response (session + API key)"))
    }

    override fun updateFields() {
        val stored = MistralQuotaClient.storedSessionFields(
            MistralSessionCookieStore.forAccount(accountKey(QuotaProviderType.MISTRAL)).load(onLoaded = ::refreshAfterLoad),
        )
        val apiKey = MistralApiKeyStore.forAccount(accountKey(QuotaProviderType.MISTRAL)).load(onLoaded = ::refreshAfterLoad)
        sessionNameField.text = stored?.sessionName.orEmpty()
        sessionValueField.text = if (stored?.sessionValue.isNullOrBlank()) "" else PLACEHOLDER
        csrfField.text = if (stored?.csrfToken.isNullOrBlank()) "" else PLACEHOLDER
        apiKeyField.text = if (apiKey.isNullOrBlank()) "" else PLACEHOLDER
        showDocumentModels(emptyList())
        updateStatus()
        refreshDocumentModels()
    }

    private fun showDocumentModels(discovered: List<String>, selection: String? = boundAccount?.extra(ProviderAccount.EXTRA_DOCUMENT_MODEL)) {
        documentModelCombo.show(
            selection,
            DocumentModels.prefixedChoices(discovered, selection, MistralOcrClient.DEFAULT_MODEL, DocumentModels::isMistralOcrModel),
        )
    }

    private fun refreshDocumentModels() {
        val accountId = accountKey(QuotaProviderType.MISTRAL)
        val generation = ++modelRefreshGeneration
        ApplicationManager.getApplication().executeOnPooledThread {
            val key = MistralApiKeyStore.forAccount(accountId).loadBlocking()
            val discovered = if (key.isNullOrBlank()) {
                emptyList()
            } else {
                DocumentModels.fetchModelIds(URI.create("${MistralSubscriptionProxyProvider.DEFAULT_UPSTREAM_BASE_URI}/models"), key)
            }
            ApplicationManager.getApplication().invokeLater({
                if (generation != modelRefreshGeneration || accountKey(QuotaProviderType.MISTRAL) != accountId) return@invokeLater
                showDocumentModels(discovered, documentModelCombo.selected() ?: boundAccount?.extra(ProviderAccount.EXTRA_DOCUMENT_MODEL))
            }, ModalityState.stateForComponent(modalityComponentProvider() ?: this))
        }
    }

    fun documentModelForStorage(): String? = documentModelCombo.storedValue()

    fun documentModelDiffers(saved: String?): Boolean = documentModelCombo.differs(saved)

    override fun updateStatus() {
        val cookieStore = MistralSessionCookieStore.forAccount(accountKey(QuotaProviderType.MISTRAL))
        val cookie = cookieStore.load(onLoaded = ::refreshAfterLoad)
        val quota = QuotaUsageService.getInstance().getLastQuota(accountKey(QuotaProviderType.MISTRAL)) as? MistralQuota
        val error = QuotaUsageService.getInstance().getLastError(accountKey(QuotaProviderType.MISTRAL))
        statusLabel.text = when {
            !cookieStore.isLoaded() -> formatStatusText("Loading credentials...", AuthStatusKind.PENDING)
            cookie.isNullOrBlank() -> formatStatusText("Mistral session cookie missing", AuthStatusKind.DISCONNECTED)
            error != null -> formatStatusText("Error: $error", AuthStatusKind.DISCONNECTED)
            quota != null -> formatStatusText("Connected", AuthStatusKind.CONNECTED)
            else -> formatStatusText("Session cookie stored securely", AuthStatusKind.CONNECTED)
        }
        statusLabel.foreground = statusLabelDefaultForeground ?: statusLabel.foreground
        statusLabel.isVisible = true
    }

    override fun updateResponseArea() {
        val raw = QuotaUsageService.getInstance().getLastResponseJson(accountKey(QuotaProviderType.MISTRAL))
        val error = QuotaUsageService.getInstance().getLastError(accountKey(QuotaProviderType.MISTRAL))
        responseViewer.text = when {
            error != null && !raw.isNullOrBlank() -> "Error: $error\n\n$raw"
            error != null -> "Error: $error"
            raw.isNullOrBlank() -> "No Mistral response yet."
            else -> raw
        }
        responseViewer.setCaretPosition(0)
    }

    private fun saveNow() {
        val current = MistralQuotaClient.storedSessionFields(MistralSessionCookieStore.forAccount(accountKey(QuotaProviderType.MISTRAL)).load())
        val currentKey = MistralApiKeyStore.forAccount(accountKey(QuotaProviderType.MISTRAL)).load()
        val sessionName = sessionNameField.text
        val sessionValue = String(sessionValueField.password).let { value ->
            if (value == PLACEHOLDER) current?.sessionValue.orEmpty() else value
        }
        val csrf = String(csrfField.password).let { value ->
            if (value == PLACEHOLDER) current?.csrfToken.orEmpty() else value
        }
        val apiKey = String(apiKeyField.password).let { if (it == PLACEHOLDER) currentKey else it }
        setPending("Saving credentials...")
        ApplicationManager.getApplication().executeOnPooledThread {
            val encoded = runCatching {
                MistralQuotaClient.encodeStoredSession(sessionName, sessionValue, csrf)
            }.getOrElse { error ->
                ApplicationManager.getApplication().invokeLater({
                    statusLabel.text = formatStatusText(error.message ?: "Invalid Mistral cookies", AuthStatusKind.DISCONNECTED)
                    statusLabel.isVisible = true
                }, ModalityState.stateForComponent(modalityComponentProvider() ?: this@MistralSettingsPanel))
                return@executeOnPooledThread
            }
            MistralSessionCookieStore.forAccount(accountKey(QuotaProviderType.MISTRAL)).save(encoded)
            MistralApiKeyStore.forAccount(accountKey(QuotaProviderType.MISTRAL)).save(apiKey)
            ApplicationManager.getApplication().invokeLater({
                updateFields()
                QuotaUsageService.getInstance().refreshAsync(accountKey(QuotaProviderType.MISTRAL))
            }, ModalityState.stateForComponent(modalityComponentProvider() ?: this@MistralSettingsPanel))
        }
    }

    private fun clearNow() {
        setPending("Clearing credentials...")
        ApplicationManager.getApplication().executeOnPooledThread {
            MistralSessionCookieStore.forAccount(accountKey(QuotaProviderType.MISTRAL)).clear()
            MistralApiKeyStore.forAccount(accountKey(QuotaProviderType.MISTRAL)).clear()
            ApplicationManager.getApplication().invokeLater({
                sessionNameField.text = ""
                sessionValueField.text = ""
                csrfField.text = ""
                apiKeyField.text = ""
                updateStatus()
                QuotaUsageService.getInstance().clearUsageData(accountKey(QuotaProviderType.MISTRAL))
            }, ModalityState.stateForComponent(modalityComponentProvider() ?: this))
        }
    }

    private fun refreshAfterLoad() {
        updateFields()
        updateResponseArea()
    }

    private fun setPending(text: String) {
        statusLabel.text = formatStatusText(text, AuthStatusKind.PENDING)
        statusLabel.isVisible = true
    }

    private fun formatStatusText(text: String, kind: AuthStatusKind): String {
        val color = when (kind) {
            AuthStatusKind.CONNECTED -> "#4CAF50"
            AuthStatusKind.DISCONNECTED -> "#F44336"
            AuthStatusKind.PENDING -> "#FFC107"
        }
        return "<html><span style=\"color: $color\">●</span>&nbsp;${QuotaUiUtil.escapeHtml(text)}</html>"
    }

    private companion object { const val PLACEHOLDER = "********" }
}
