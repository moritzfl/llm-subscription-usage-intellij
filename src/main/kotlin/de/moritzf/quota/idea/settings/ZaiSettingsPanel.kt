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
    private val apiKey =
        storedSecret(
            listOf(apiKeyField),
            read = { ZaiApiKeyStore.forAccount(it).loadBlocking() },
            write = { id, value ->
                val store = ZaiApiKeyStore.forAccount(id)
                saveOrClear(value, store::save, store::clear)
                if (value == null) QuotaUsageService.getInstance().clearUsageData(id)
                else QuotaUsageService.getInstance().refreshAsync(id)
            },
        )
    private val zaiJsonViewer = createResponseViewer()

    init {
        val configPanel = panel {
            row { statusCell(zaiStatusLabel) }
            row("API key:") {
                cell(apiKeyField).resizableColumn().align(AlignX.FILL)
                cell(apiKey.checkButton { _, key -> { ZaiQuotaClient().fetchQuota(key) } })
            }
            row("") {
                cell(apiKey.hint)
                cell(apiKey.checkStatus)
            }
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
                    ),
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
                    ),
                )
            }
        }

        install(configPanel, createResponseSection(zaiJsonViewer))
    }

    override fun updateFields() {
        apiKey.show(accountKey(QuotaProviderType.ZAI))
        showDocumentModels(emptyList())
        showVisionModels(emptyList())
        updateStatus()
        refreshDocumentModels()
    }

    override fun testConnection(): ProviderTestConnection =
        ProviderTestConnection(
            checkNotNull(boundAccount),
            apiKey.override(accountKey(QuotaProviderType.ZAI)),
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
                        URI.create("${ZaiSubscriptionProxyProvider.STANDARD_API_BASE_URI}/models"),
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

    override fun testApiKeyDrafts(): Map<String, String> = apiKey.drafts()

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
                    statusDotHtml(message("settings.status.noKey"), AuthStatusKind.DISCONNECTED)
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
                    statusDotHtml(message("settings.status.keyStored"), AuthStatusKind.CONNECTED)
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
}
