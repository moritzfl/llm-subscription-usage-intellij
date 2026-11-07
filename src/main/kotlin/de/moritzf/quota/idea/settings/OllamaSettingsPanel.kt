package de.moritzf.quota.idea.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.panel
import de.moritzf.quota.idea.common.QuotaBundle.message
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.ollama.OllamaApiKeyStore
import de.moritzf.quota.ollama.OllamaQuota
import de.moritzf.quota.ollama.OllamaQuotaClient
import de.moritzf.quota.ollama.OllamaResetSchedule
import de.moritzf.quota.ollama.OllamaVisionModels
import de.moritzf.quota.shared.JsonSupport
import java.awt.Color
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.JComponent

/** Ollama Cloud settings tab — API key only (quota, web search, and proxy). */
internal class OllamaSettingsPanel(
    private val modalityComponentProvider: () -> JComponent?,
    private val statusLabelDefaultForeground: Color? = null,
) : ProviderSettingsPanel() {
    val monthlyResetField =
        JBTextField().apply {
            columns = 40
            toolTipText = "Optional ISO-8601 UTC reset, or a paste of the ollama.com/settings page"
            addFocusListener(
                object : FocusAdapter() {
                    override fun focusLost(e: FocusEvent) {
                        collapsePastedReset()
                    }
                },
            )
        }
    private val apiKeyField =
        JBPasswordField().apply {
            columns = 40
            toolTipText = "Ollama API key from ollama.com/settings/keys"
        }
    private val ollamaStatusLabel = JBLabel().apply { isVisible = false }
    private val apiKey =
        storedSecret(
            listOf(apiKeyField),
            read = { OllamaApiKeyStore.forAccount(it).loadBlocking() },
            write = { id, value ->
                val store = OllamaApiKeyStore.forAccount(id)
                saveOrClear(value, store::save, store::clear)
                if (value == null) QuotaUsageService.getInstance().clearUsageData(id)
                else QuotaUsageService.getInstance().refreshAsync(id)
            },
        )
    override val visionModelCombo = VisionModelCombo()
    private val ollamaJsonViewer = createResponseViewer()
    private var modelRefreshGeneration = 0

    init {
        val ollamaConfigPanel = panel {
            row { statusCell(ollamaStatusLabel) }
            row {
                text(
                    "Create an API key at ollama.com/settings/keys. Used for quota, MCP web search, and the local proxy.",
                )
            }
            row("API key:") {
                cell(apiKeyField).resizableColumn().align(AlignX.FILL)
                cell(apiKey.checkButton { _, key -> { OllamaQuotaClient().fetchQuota(key) } })
            }
            row("") {
                cell(apiKey.hint)
                cell(apiKey.checkStatus)
            }
            row("Monthly reset:") {
                cell(monthlyResetField)
                    .resizableColumn()
                    .align(AlignX.FILL)
                    .comment(
                        "Optional. Paste an ISO-8601 UTC time, or the ollama.com/settings page HTML. " +
                            "The Monthly usage Resets data-time is kept; other timestamps are ignored. " +
                            "Fallback only when the API omits the monthly reset. Same day and clock each calendar month.",
                        MAX_LINE_LENGTH_WORD_WRAP,
                    )
            }
            row(message("settings.model.vision.label")) {
                cell(visionModelCombo.combo)
                    .align(AlignX.FILL)
                    .resizableColumn()
                    .comment(message("settings.model.vision.help"), MAX_LINE_LENGTH_WORD_WRAP)
                cell(
                    visionTestButton(
                        de.moritzf.quota.idea.mcp.VisionProvider.OLLAMA,
                        visionModelCombo,
                        modalityComponentProvider,
                    ),
                )
            }
        }

        install(ollamaConfigPanel, createResponseSection(ollamaJsonViewer))
    }

    override fun writeAccountFields(account: ProviderAccount) {
        account.setExtra(
            ProviderAccount.EXTRA_OLLAMA_MONTHLY_RESET,
            normalizedMonthlyResetForStorage(),
        )
        super.writeAccountFields(account)
    }

    override fun accountFieldsModified(
        account: ProviderAccount,
        saved: ProviderAccount?,
        state: QuotaSettingsState,
    ): Boolean =
        monthlyResetField.text.trim() !=
            (saved?.extra(ProviderAccount.EXTRA_OLLAMA_MONTHLY_RESET)
                    ?: account.extra(ProviderAccount.EXTRA_OLLAMA_MONTHLY_RESET))
                .orEmpty() || super.accountFieldsModified(account, saved, state)

    /**
     * The canonical reset, or the text as typed when it does not parse: switching accounts must
     * keep the draft, and Apply rejects it ([QuotaSettingsDraft.validate]).
     */
    private fun normalizedMonthlyResetForStorage(): String? {
        collapsePastedReset()
        val raw = monthlyResetField.text.trim()
        if (raw.isEmpty()) return null
        return OllamaResetSchedule.parseMonthlyAnchor(raw)?.toString() ?: raw
    }

    private fun collapsePastedReset() {
        val raw = monthlyResetField.text
        if (!raw.contains('<') && !raw.contains("data-time", ignoreCase = true)) return
        val canonical = OllamaResetSchedule.parseMonthlyAnchor(raw)?.toString() ?: return
        if (monthlyResetField.text != canonical) {
            monthlyResetField.text = canonical
        }
    }

    override fun updateFields() {
        apiKey.show(accountKey(QuotaProviderType.OLLAMA))
        monthlyResetField.text =
            boundAccount?.extra(ProviderAccount.EXTRA_OLLAMA_MONTHLY_RESET).orEmpty()
        showVisionModels(emptyMap())
        updateStatus()
        refreshVisionModels()
    }

    override fun testConnection(): ProviderTestConnection =
        ProviderTestConnection(
            checkNotNull(boundAccount),
            apiKey.override(accountKey(QuotaProviderType.OLLAMA)),
        )

    private fun showVisionModels(
        discovered: Map<String, Boolean?>,
        selection: String? = boundAccount?.extra(ProviderAccount.EXTRA_VISION_MODEL),
    ) {
        visionModelCombo.show(selection, OllamaVisionModels.choices(discovered, selection))
    }

    private fun refreshVisionModels() {
        val isCurrent = accountUiGuard()
        val modality = ModalityState.stateForComponent(modalityComponentProvider() ?: this)
        val accountId = accountKey(QuotaProviderType.OLLAMA)
        val generation = ++modelRefreshGeneration
        ApplicationManager.getApplication().executeOnPooledThread {
            val key = OllamaApiKeyStore.forAccount(accountId).loadBlocking()
            val discovered =
                if (key.isNullOrBlank()) {
                    emptyMap()
                } else {
                    runCatching { OllamaVisionModels().discover(key) }.getOrDefault(emptyMap())
                }
            ApplicationManager.getApplication()
                .invokeLater(
                    {
                        if (!isCurrent() || generation != modelRefreshGeneration) return@invokeLater
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
        val apiKeyStore = OllamaApiKeyStore.forAccount(accountKey(QuotaProviderType.OLLAMA))
        val apiKey = apiKeyStore.load(onLoaded = { if (isCurrent()) updateStatus() })
        val ollamaQuota =
            QuotaUsageService.getInstance().getLastQuota(accountKey(QuotaProviderType.OLLAMA))
                as? OllamaQuota
        val ollamaError =
            QuotaUsageService.getInstance().getLastError(accountKey(QuotaProviderType.OLLAMA))

        when {
            !apiKeyStore.isLoaded() -> {
                ollamaStatusLabel.text =
                    statusDotHtml("Loading Ollama credentials...", AuthStatusKind.PENDING)
                ollamaStatusLabel.foreground =
                    statusLabelDefaultForeground ?: ollamaStatusLabel.foreground
            }
            apiKey.isNullOrBlank() -> {
                ollamaStatusLabel.text =
                    statusDotHtml(message("settings.status.noKey"), AuthStatusKind.DISCONNECTED)
                ollamaStatusLabel.foreground =
                    statusLabelDefaultForeground ?: ollamaStatusLabel.foreground
            }
            ollamaError != null -> {
                ollamaStatusLabel.text =
                    statusDotHtml("Error: $ollamaError", AuthStatusKind.DISCONNECTED)
                ollamaStatusLabel.foreground =
                    statusLabelDefaultForeground ?: ollamaStatusLabel.foreground
            }
            ollamaQuota != null -> {
                ollamaStatusLabel.text = statusDotHtml("Connected", AuthStatusKind.CONNECTED)
                ollamaStatusLabel.foreground =
                    statusLabelDefaultForeground ?: ollamaStatusLabel.foreground
            }
            else -> {
                ollamaStatusLabel.text =
                    statusDotHtml(message("settings.status.keyStored"), AuthStatusKind.CONNECTED)
                ollamaStatusLabel.foreground =
                    statusLabelDefaultForeground ?: ollamaStatusLabel.foreground
            }
        }
        ollamaStatusLabel.isVisible = true
    }

    override fun updateResponseArea() {
        val quota =
            QuotaUsageService.getInstance().getLastQuota(accountKey(QuotaProviderType.OLLAMA))
                as? OllamaQuota
        val error =
            QuotaUsageService.getInstance().getLastError(accountKey(QuotaProviderType.OLLAMA))
        val rawJson =
            QuotaUsageService.getInstance()
                .getLastResponseJson(accountKey(QuotaProviderType.OLLAMA))

        ollamaJsonViewer.text =
            when {
                error != null && !rawJson.isNullOrBlank() -> "Error: $error\n\n$rawJson"
                error != null -> "Error: $error"
                quota == null -> "No Ollama response yet."
                !rawJson.isNullOrBlank() -> rawJson
                else -> {
                    try {
                        JsonSupport.json.encodeToString(OllamaQuota.serializer(), quota)
                    } catch (exception: Exception) {
                        "Could not serialize response: ${exception.message}"
                    }
                }
            }
        ollamaJsonViewer.setCaretPosition(0)
    }
}
