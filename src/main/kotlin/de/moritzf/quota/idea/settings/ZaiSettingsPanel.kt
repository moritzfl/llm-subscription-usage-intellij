package de.moritzf.quota.idea.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.zai.ZaiOcrClient
import de.moritzf.quota.zai.ZaiQuota
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.zai.ZaiApiKeyStore
import de.moritzf.quota.shared.DocumentModels
import de.moritzf.quota.zai.ZaiQuotaClient
import de.moritzf.quota.zai.proxy.ZaiSubscriptionProxyProvider
import java.net.URI
import java.awt.Color
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JComponent

/**
 * Z.ai settings tab.
 */
internal class ZaiSettingsPanel(
    private val modalityComponentProvider: () -> JComponent?,
    private val statusLabelDefaultForeground: Color? = null,
) : ProviderSettingsPanel() {
    private val documentModelCombo = DocumentModelCombo(ZaiOcrClient.DEFAULT_MODEL, vision = false)
    private var modelRefreshGeneration = 0
    private val apiKeyField = JBPasswordField().apply {
        columns = 40
        toolTipText = "Z.ai API key from the Z.ai console"
    }
    private val zaiStatusLabel = JBLabel().apply { isVisible = false }
    private val zaiJsonViewer = createResponseViewer()
    private val validationGeneration = AtomicLong(0)
    private var awaitingApiKeyLoadRefresh: Boolean = false

    init {
        val configPanel = panel {
            row {
                cell(zaiStatusLabel)
            }
            row("API key:") {
                cell(apiKeyField)
                    .resizableColumn()
                    .align(AlignX.FILL)
            }
            row("Document model:") {
                cell(documentModelCombo.combo).align(AlignX.FILL).resizableColumn()
                    .comment("Loaded from the Z.ai model list. Only glm-ocr models. - turns conversion off.")
                cell(DocumentTestButton(de.moritzf.quota.idea.mcp.DocumentToMarkdownProvider.ZAI, { documentModelCombo.selected().orEmpty() }, modalityComponentProvider))
            }
            row {
                button("Save") {
                    val apiKey = String(apiKeyField.password)
                    if (apiKey.isNotBlank() && apiKey != API_KEY_PLACEHOLDER) {
                        setZaiPendingStatus("Validating API key...")
                        saveApiKeyNow(apiKey)
                    }
                }
                button("Clear") {
                    apiKeyField.text = ""
                    setZaiPendingStatus("Clearing API key...")
                    clearApiKeyNow()
                }
            }
        }

        install(configPanel, createResponseSection(zaiJsonViewer))
    }

    override fun updateFields() {
        val apiKey = ZaiApiKeyStore.forAccount(accountKey(QuotaProviderType.ZAI)).load(onLoaded = ::refreshAfterApiKeyLoad)
        apiKeyField.text = if (apiKey.isNullOrBlank()) "" else API_KEY_PLACEHOLDER
        showDocumentModels(emptyList())
        updateStatus()
        refreshDocumentModels()
    }

    fun documentModelForStorage(): String? = documentModelCombo.storedValue()

    fun documentModelDiffers(saved: String?): Boolean = documentModelCombo.differs(saved)

    private fun showDocumentModels(discovered: List<String>, selection: String? = boundAccount?.extra(ProviderAccount.EXTRA_DOCUMENT_MODEL)) {
        documentModelCombo.show(
            selection,
            DocumentModels.prefixedChoices(discovered, selection, ZaiOcrClient.DEFAULT_MODEL, DocumentModels::isZaiOcrModel),
        )
    }

    private fun refreshDocumentModels() {
        val accountId = accountKey(QuotaProviderType.ZAI)
        val generation = ++modelRefreshGeneration
        ApplicationManager.getApplication().executeOnPooledThread {
            val key = ZaiApiKeyStore.forAccount(accountId).loadBlocking()
            val discovered = if (key.isNullOrBlank()) {
                emptyList()
            } else {
                DocumentModels.fetchModelIds(URI.create("${ZaiSubscriptionProxyProvider.DEFAULT_UPSTREAM_BASE_URI}/models"), key)
            }
            ApplicationManager.getApplication().invokeLater({
                if (generation != modelRefreshGeneration || accountKey(QuotaProviderType.ZAI) != accountId) return@invokeLater
                showDocumentModels(discovered, documentModelCombo.selected() ?: boundAccount?.extra(ProviderAccount.EXTRA_DOCUMENT_MODEL))
            }, ModalityState.stateForComponent(modalityComponentProvider() ?: this))
        }
    }

    override fun updateStatus() {
        val apiKeyStore = ZaiApiKeyStore.forAccount(accountKey(QuotaProviderType.ZAI))
        val apiKey = apiKeyStore.load(onLoaded = ::refreshAfterApiKeyLoad)
        val quota = QuotaUsageService.getInstance().getLastQuota(accountKey(QuotaProviderType.ZAI)) as? ZaiQuota
        val error = QuotaUsageService.getInstance().getLastError(accountKey(QuotaProviderType.ZAI))

        when {
            !apiKeyStore.isLoaded() -> {
                zaiStatusLabel.text = formatStatusText("Loading API key...", AuthStatusKind.PENDING)
                zaiStatusLabel.foreground = statusLabelDefaultForeground ?: zaiStatusLabel.foreground
            }
            apiKey.isNullOrBlank() -> {
                zaiStatusLabel.text = formatStatusText("No Z.ai API key configured", AuthStatusKind.DISCONNECTED)
                zaiStatusLabel.foreground = statusLabelDefaultForeground ?: zaiStatusLabel.foreground
            }
            error != null -> {
                zaiStatusLabel.text = formatStatusText("Error: $error", AuthStatusKind.DISCONNECTED)
                zaiStatusLabel.foreground = statusLabelDefaultForeground ?: zaiStatusLabel.foreground
            }
            quota != null -> {
                zaiStatusLabel.text = formatStatusText("Connected", AuthStatusKind.CONNECTED)
                zaiStatusLabel.foreground = statusLabelDefaultForeground ?: zaiStatusLabel.foreground
            }
            else -> {
                zaiStatusLabel.text = formatStatusText("API key stored securely", AuthStatusKind.CONNECTED)
                zaiStatusLabel.foreground = statusLabelDefaultForeground ?: zaiStatusLabel.foreground
            }
        }
        zaiStatusLabel.isVisible = true
    }

    override fun updateResponseArea() {
        val quota = QuotaUsageService.getInstance().getLastQuota(accountKey(QuotaProviderType.ZAI)) as? ZaiQuota
        val error = QuotaUsageService.getInstance().getLastError(accountKey(QuotaProviderType.ZAI))
        val rawJson = QuotaUsageService.getInstance().getLastResponseJson(accountKey(QuotaProviderType.ZAI))

        zaiJsonViewer.text = when {
            error != null && !rawJson.isNullOrBlank() -> "Error: $error\n\n$rawJson"
            error != null -> "Error: $error"
            quota == null -> "No Z.ai response yet."
            !rawJson.isNullOrBlank() -> rawJson
            else -> {
                try {
                    de.moritzf.quota.shared.JsonSupport.json.encodeToString(
                        ZaiQuota.serializer(),
                        quota,
                    )
                } catch (exception: Exception) {
                    "Could not serialize response: ${exception.message}"
                }
            }
        }
        zaiJsonViewer.setCaretPosition(0)
    }

    private fun saveApiKeyNow(apiKey: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { ZaiApiKeyStore.forAccount(accountKey(QuotaProviderType.ZAI)).save(apiKey) }
            ApplicationManager.getApplication().invokeLater({
                result.fold(
                    onSuccess = {
                        apiKeyField.text = API_KEY_PLACEHOLDER
                        validateApiKeyNow(apiKey)
                        QuotaUsageService.getInstance().refreshAsync(accountKey(QuotaProviderType.ZAI))
                    },
                    onFailure = { error ->
                        zaiStatusLabel.text = formatStatusText("Error: ${error.message ?: "Could not save API key"}", AuthStatusKind.DISCONNECTED)
                        zaiStatusLabel.foreground = statusLabelDefaultForeground ?: zaiStatusLabel.foreground
                        zaiStatusLabel.isVisible = true
                    },
                )
            }, ModalityState.stateForComponent(modalityComponentProvider() ?: this@ZaiSettingsPanel))
        }
    }

    private fun clearApiKeyNow() {
        ApplicationManager.getApplication().executeOnPooledThread {
            ZaiApiKeyStore.forAccount(accountKey(QuotaProviderType.ZAI)).clear()
            ApplicationManager.getApplication().invokeLater({
                updateStatus()
                QuotaUsageService.getInstance().clearUsageData(accountKey(QuotaProviderType.ZAI))
            }, ModalityState.stateForComponent(modalityComponentProvider() ?: this@ZaiSettingsPanel))
        }
    }

    private fun validateApiKeyNow(apiKey: String) {
        val generation = validationGeneration.incrementAndGet()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { ZaiQuotaClient().fetchQuota(apiKey) }
            ApplicationManager.getApplication().invokeLater({
                if (generation != validationGeneration.get()) {
                    return@invokeLater
                }
                result.fold(
                    onSuccess = {
                        zaiStatusLabel.text = formatStatusText("Connected", AuthStatusKind.CONNECTED)
                        zaiStatusLabel.foreground = statusLabelDefaultForeground ?: zaiStatusLabel.foreground
                        zaiStatusLabel.isVisible = true
                    },
                    onFailure = { error ->
                        zaiStatusLabel.text = formatStatusText("Error: ${error.message ?: "Validation failed"}", AuthStatusKind.DISCONNECTED)
                        zaiStatusLabel.foreground = statusLabelDefaultForeground ?: zaiStatusLabel.foreground
                        zaiStatusLabel.isVisible = true
                    },
                )
            }, ModalityState.stateForComponent(modalityComponentProvider() ?: this@ZaiSettingsPanel))
        }
    }

    private fun setZaiPendingStatus(text: String) {
        zaiStatusLabel.text = formatStatusText(text, AuthStatusKind.PENDING)
        zaiStatusLabel.foreground = statusLabelDefaultForeground ?: zaiStatusLabel.foreground
        zaiStatusLabel.isVisible = true
    }

    private fun refreshAfterApiKeyLoad() {
        if (awaitingApiKeyLoadRefresh) {
            awaitingApiKeyLoadRefresh = false
            return
        }
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
        private const val API_KEY_PLACEHOLDER = "********"
    }
}
