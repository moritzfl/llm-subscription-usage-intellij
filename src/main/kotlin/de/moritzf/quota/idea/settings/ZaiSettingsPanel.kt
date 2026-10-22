package de.moritzf.quota.idea.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.panel
import de.moritzf.quota.idea.common.QuotaBundle.message
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.zai.ZaiApiKeyStore
import de.moritzf.quota.shared.DocumentModels
import de.moritzf.quota.zai.ZaiOcrClient
import de.moritzf.quota.zai.ZaiQuota
import de.moritzf.quota.zai.ZaiQuotaClient
import de.moritzf.quota.zai.proxy.ZaiSubscriptionProxyProvider
import java.awt.Color
import java.net.URI
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JComponent

/** Z.ai settings tab. */
internal class ZaiSettingsPanel(
    private val modalityComponentProvider: () -> JComponent?,
    private val statusLabelDefaultForeground: Color? = null,
) : ProviderSettingsPanel() {
    override val documentModelCombo = DocumentModelCombo(ZaiOcrClient.DEFAULT_MODEL, vision = false)
    override val visionModelCombo = VisionModelCombo()
    private var modelRefreshGeneration = 0
    private val apiKeyField =
        JBPasswordField().apply {
            columns = 40
            toolTipText = "Z.ai API key from the Z.ai console"
        }
    private val zaiStatusLabel = JBLabel().apply { isVisible = false }
    private val apiKeyDraft = credentialDraft(apiKeyField)
    private val zaiJsonViewer = createResponseViewer()
    private val validationGeneration = AtomicLong(0)

    init {
        val configPanel = panel {
            row { statusCell(zaiStatusLabel) }
            row("API key:") { cell(apiKeyField).resizableColumn().align(AlignX.FILL) }
            row(message("settings.model.document.label")) {
                cell(documentModelCombo.combo)
                    .align(AlignX.FILL)
                    .resizableColumn()
                    .comment(message("settings.model.document.help"), MAX_LINE_LENGTH_WORD_WRAP)
                cell(
                    documentTestButton(
                        de.moritzf.quota.idea.mcp.DocumentToMarkdownProvider.ZAI,
                        { documentModelCombo.selected().orEmpty() },
                        modalityComponentProvider,
                        documentModelCombo.combo,
                    )
                )
            }
            row(message("settings.model.vision.label")) {
                cell(visionModelCombo.combo)
                    .align(AlignX.FILL)
                    .resizableColumn()
                    .comment(message("settings.model.vision.help"), MAX_LINE_LENGTH_WORD_WRAP)
                cell(
                    visionTestButton(
                        de.moritzf.quota.idea.mcp.VisionProvider.ZAI,
                        visionModelCombo,
                        modalityComponentProvider,
                    )
                )
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
        loadCredentialField()
        showDocumentModels(emptyList())
        showVisionModels(emptyList())
        updateStatus()
        refreshDocumentModels()
    }

    private fun loadCredentialField() {
        val id = accountKey(QuotaProviderType.ZAI)
        val isCurrent = accountUiGuard()
        val apiKey =
            ZaiApiKeyStore.forAccount(id)
                .load(
                    onLoaded = {
                        if (isCurrent()) {
                            loadCredentialField()
                            updateStatus()
                            updateResponseArea()
                            refreshDocumentModels()
                        }
                    }
                )
        apiKeyDraft.show(id, if (apiKey.isNullOrBlank()) "" else API_KEY_PLACEHOLDER)
    }

    override fun testConnection(): ProviderTestConnection =
        ProviderTestConnection(
            checkNotNull(boundAccount),
            String(apiKeyField.password).takeUnless { it == API_KEY_PLACEHOLDER },
        )

    private fun showDocumentModels(
        discovered: List<String>,
        selection: String? = boundAccount?.extra(ProviderAccount.EXTRA_DOCUMENT_MODEL),
    ) {
        documentModelCombo.show(
            selection,
            DocumentModels.prefixedChoices(
                discovered,
                selection,
                ZaiOcrClient.DEFAULT_MODEL,
                DocumentModels::isZaiOcrModel,
            ),
        )
    }

    private fun showVisionModels(
        discovered: List<String>,
        selection: String? = boundAccount?.extra(ProviderAccount.EXTRA_VISION_MODEL),
    ) {
        visionModelCombo.show(selection, discovered)
    }

    private fun refreshDocumentModels() {
        val isCurrent = accountUiGuard()
        val modality = ModalityState.stateForComponent(modalityComponentProvider() ?: this)
        val accountId = accountKey(QuotaProviderType.ZAI)
        val generation = ++modelRefreshGeneration
        ApplicationManager.getApplication().executeOnPooledThread {
            val key = ZaiApiKeyStore.forAccount(accountId).loadBlocking()
            val discovered =
                if (key.isNullOrBlank()) {
                    emptyList()
                } else {
                    DocumentModels.fetchModelIds(
                        URI.create(
                            "${ZaiSubscriptionProxyProvider.DEFAULT_UPSTREAM_BASE_URI}/models"
                        ),
                        key,
                    )
                }
            ApplicationManager.getApplication()
                .invokeLater(
                    {
                        if (!isCurrent() || generation != modelRefreshGeneration) return@invokeLater
                        showDocumentModels(
                            discovered,
                            documentModelCombo.selected()
                                ?: boundAccount?.extra(ProviderAccount.EXTRA_DOCUMENT_MODEL),
                        )
                        showVisionModels(
                            discovered,
                            visionModelCombo.selected()
                                ?: boundAccount?.extra(ProviderAccount.EXTRA_VISION_MODEL),
                        )
                    },
                    modality,
                )
        }
    }

    override fun testApiKeyDrafts(): Map<String, String> =
        apiKeyDraft.snapshot().filterValues { it != API_KEY_PLACEHOLDER }

    override fun updateStatus() {
        val isCurrent = accountUiGuard()
        val apiKeyStore = ZaiApiKeyStore.forAccount(accountKey(QuotaProviderType.ZAI))
        val apiKey = apiKeyStore.load(onLoaded = { if (isCurrent()) updateStatus() })
        val quota =
            QuotaUsageService.getInstance().getLastQuota(accountKey(QuotaProviderType.ZAI))
                as? ZaiQuota
        val error = QuotaUsageService.getInstance().getLastError(accountKey(QuotaProviderType.ZAI))

        when {
            !apiKeyStore.isLoaded() -> {
                zaiStatusLabel.text = statusDotHtml("Loading API key...", AuthStatusKind.PENDING)
                zaiStatusLabel.foreground =
                    statusLabelDefaultForeground ?: zaiStatusLabel.foreground
            }
            apiKey.isNullOrBlank() -> {
                zaiStatusLabel.text =
                    statusDotHtml("No Z.ai API key configured", AuthStatusKind.DISCONNECTED)
                zaiStatusLabel.foreground =
                    statusLabelDefaultForeground ?: zaiStatusLabel.foreground
            }
            error != null -> {
                zaiStatusLabel.text = statusDotHtml("Error: $error", AuthStatusKind.DISCONNECTED)
                zaiStatusLabel.foreground =
                    statusLabelDefaultForeground ?: zaiStatusLabel.foreground
            }
            quota != null -> {
                zaiStatusLabel.text = statusDotHtml("Connected", AuthStatusKind.CONNECTED)
                zaiStatusLabel.foreground =
                    statusLabelDefaultForeground ?: zaiStatusLabel.foreground
            }
            else -> {
                zaiStatusLabel.text =
                    statusDotHtml("API key stored securely", AuthStatusKind.CONNECTED)
                zaiStatusLabel.foreground =
                    statusLabelDefaultForeground ?: zaiStatusLabel.foreground
            }
        }
        zaiStatusLabel.isVisible = true
    }

    override fun updateResponseArea() {
        val quota =
            QuotaUsageService.getInstance().getLastQuota(accountKey(QuotaProviderType.ZAI))
                as? ZaiQuota
        val error = QuotaUsageService.getInstance().getLastError(accountKey(QuotaProviderType.ZAI))
        val rawJson =
            QuotaUsageService.getInstance().getLastResponseJson(accountKey(QuotaProviderType.ZAI))

        zaiJsonViewer.text =
            when {
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
        val id = accountKey(QuotaProviderType.ZAI)
        val submitted = String(apiKeyField.password)
        val isCurrent = accountUiGuard()
        val modality = ModalityState.stateForComponent(modalityComponentProvider() ?: this)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                ZaiApiKeyStore.forAccount(id).save(apiKey)
                QuotaUsageService.getInstance().refreshAsync(id)
            }
            ApplicationManager.getApplication()
                .invokeLater(
                    {
                        if (result.isSuccess) apiKeyDraft.saved(id, submitted)
                        if (!isCurrent()) return@invokeLater
                        result.fold(
                            onSuccess = {
                                apiKeyDraft.show(id, API_KEY_PLACEHOLDER)
                                validateApiKeyNow(apiKey)
                            },
                            onFailure = { error ->
                                zaiStatusLabel.text =
                                    statusDotHtml(
                                        "Error: ${error.message ?: "Could not save API key"}",
                                        AuthStatusKind.DISCONNECTED,
                                    )
                                zaiStatusLabel.foreground =
                                    statusLabelDefaultForeground ?: zaiStatusLabel.foreground
                                zaiStatusLabel.isVisible = true
                            },
                        )
                    },
                    modality,
                )
        }
    }

    private fun clearApiKeyNow() {
        val id = accountKey(QuotaProviderType.ZAI)
        val isCurrent = accountUiGuard()
        val submitted = String(apiKeyField.password)
        val modality = ModalityState.stateForComponent(modalityComponentProvider() ?: this)
        validationGeneration.incrementAndGet()
        ApplicationManager.getApplication().executeOnPooledThread {
            ZaiApiKeyStore.forAccount(id).clear()
            QuotaUsageService.getInstance().clearUsageData(id)
            ApplicationManager.getApplication()
                .invokeLater(
                    {
                        apiKeyDraft.saved(id, submitted)
                        if (isCurrent()) {
                            apiKeyDraft.show(id, "")
                            updateStatus()
                        }
                    },
                    modality,
                )
        }
    }

    private fun validateApiKeyNow(apiKey: String) {
        val isCurrent = accountUiGuard()
        val modality = ModalityState.stateForComponent(modalityComponentProvider() ?: this)
        val generation = validationGeneration.incrementAndGet()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { ZaiQuotaClient().fetchQuota(apiKey) }
            ApplicationManager.getApplication()
                .invokeLater(
                    {
                        if (!isCurrent() || generation != validationGeneration.get()) {
                            return@invokeLater
                        }
                        result.fold(
                            onSuccess = {
                                zaiStatusLabel.text =
                                    statusDotHtml("Connected", AuthStatusKind.CONNECTED)
                                zaiStatusLabel.foreground =
                                    statusLabelDefaultForeground ?: zaiStatusLabel.foreground
                                zaiStatusLabel.isVisible = true
                            },
                            onFailure = { error ->
                                zaiStatusLabel.text =
                                    statusDotHtml(
                                        "Error: ${error.message ?: "Validation failed"}",
                                        AuthStatusKind.DISCONNECTED,
                                    )
                                zaiStatusLabel.foreground =
                                    statusLabelDefaultForeground ?: zaiStatusLabel.foreground
                                zaiStatusLabel.isVisible = true
                            },
                        )
                    },
                    modality,
                )
        }
    }

    private fun setZaiPendingStatus(text: String) {
        zaiStatusLabel.text = statusDotHtml(text, AuthStatusKind.PENDING)
        zaiStatusLabel.foreground = statusLabelDefaultForeground ?: zaiStatusLabel.foreground
        zaiStatusLabel.isVisible = true
    }

    private companion object {
        private const val API_KEY_PLACEHOLDER = "********"
    }
}
