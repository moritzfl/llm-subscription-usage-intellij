package de.moritzf.quota.idea.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.ConfigurationException
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
import java.util.concurrent.atomic.AtomicLong
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
                }
            )
        }
    private val apiKeyField =
        JBPasswordField().apply {
            columns = 40
            toolTipText = "Ollama API key from ollama.com/settings/keys"
        }
    private val ollamaStatusLabel = JBLabel().apply { isVisible = false }
    private val apiKeyDraft = credentialDraft(apiKeyField)
    private val visionModelCombo = VisionModelCombo()
    private val ollamaJsonViewer = createResponseViewer()
    private val validationGeneration = AtomicLong(0)
    private var modelRefreshGeneration = 0

    init {
        val ollamaConfigPanel = panel {
            row { cell(ollamaStatusLabel).align(AlignX.FILL).resizableColumn() }
            row {
                text(
                    "Create an API key at ollama.com/settings/keys. Used for quota, MCP web search, and the local proxy."
                )
            }
            row("API key:") { cell(apiKeyField).resizableColumn().align(AlignX.FILL) }
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
                    )
                )
            }
            row {
                button("Save API Key") { saveApiKeyNow() }
                button("Clear API Key") { clearApiKeyNow() }
            }
        }

        install(ollamaConfigPanel, createResponseSection(ollamaJsonViewer))
    }

    fun normalizedMonthlyResetForStorage(): String? {
        collapsePastedReset()
        val raw = monthlyResetField.text.trim()
        if (raw.isEmpty()) return null
        return OllamaResetSchedule.parseMonthlyAnchor(raw)?.toString()
            ?: throw ConfigurationException(
                "Ollama monthly reset must be ISO-8601 UTC, or ollama.com/settings HTML containing the Monthly usage Resets data-time."
            )
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
        loadCredentialField()
        monthlyResetField.text =
            boundAccount?.extra(ProviderAccount.EXTRA_OLLAMA_MONTHLY_RESET).orEmpty()
        showVisionModels(emptyMap())
        updateStatus()
        refreshVisionModels()
    }

    private fun loadCredentialField() {
        val id = accountKey(QuotaProviderType.OLLAMA)
        val isCurrent = accountUiGuard()
        val apiKeyStore = OllamaApiKeyStore.forAccount(id)
        val apiKey =
            apiKeyStore.load(
                onLoaded = {
                    if (isCurrent()) {
                        loadCredentialField()
                        updateStatus()
                        updateResponseArea()
                        refreshVisionModels()
                    }
                }
            )
        apiKeyDraft.show(id, if (apiKey.isNullOrBlank()) "" else API_KEY_PLACEHOLDER)
    }

    fun visionModelForStorage(): String? = visionModelCombo.storedValue()

    override fun testConnection(): ProviderTestConnection =
        ProviderTestConnection(
            checkNotNull(boundAccount),
            String(apiKeyField.password).takeUnless { it == API_KEY_PLACEHOLDER },
        )

    fun visionModelDiffers(saved: String?): Boolean = visionModelCombo.differs(saved)

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

    override fun testApiKeyDrafts(): Map<String, String> =
        apiKeyDraft.snapshot().filterValues { it != API_KEY_PLACEHOLDER }

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
                    statusDotHtml("No API key configured", AuthStatusKind.DISCONNECTED)
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
                    statusDotHtml("API key stored securely", AuthStatusKind.CONNECTED)
                ollamaStatusLabel.foreground =
                    statusLabelDefaultForeground ?: ollamaStatusLabel.foreground
            }
        }
        ollamaStatusLabel.isVisible = true
    }

    private fun saveApiKeyNow() {
        val id = accountKey(QuotaProviderType.OLLAMA)
        val submitted = String(apiKeyField.password)
        val apiKey = submitted.trim()
        if (apiKey.isNotBlank() && apiKey != API_KEY_PLACEHOLDER) {
            OllamaApiKeyStore.forAccount(id).save(apiKey)
            apiKeyDraft.saved(id, submitted)
            apiKeyDraft.show(id, API_KEY_PLACEHOLDER)
            setOllamaPendingStatus()
            validateApiKeyNow(apiKey)
            QuotaUsageService.getInstance().refreshAsync(accountKey(QuotaProviderType.OLLAMA))
        }
    }

    private fun clearApiKeyNow() {
        val id = accountKey(QuotaProviderType.OLLAMA)
        OllamaApiKeyStore.forAccount(id).clear()
        apiKeyDraft.saved(id, String(apiKeyField.password))
        apiKeyDraft.show(id, "")
        validationGeneration.incrementAndGet()
        updateStatus()
        QuotaUsageService.getInstance().clearUsageData(accountKey(QuotaProviderType.OLLAMA))
    }

    private fun validateApiKeyNow(apiKey: String) {
        val isCurrent = accountUiGuard()
        val modality = ModalityState.stateForComponent(modalityComponentProvider() ?: this)
        val generation = validationGeneration.incrementAndGet()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { OllamaQuotaClient().fetchQuota(apiKey) }
            ApplicationManager.getApplication()
                .invokeLater(
                    {
                        if (!isCurrent() || generation != validationGeneration.get()) {
                            return@invokeLater
                        }
                        result.fold(
                            onSuccess = {
                                ollamaStatusLabel.text =
                                    statusDotHtml("Connected", AuthStatusKind.CONNECTED)
                                ollamaStatusLabel.foreground =
                                    statusLabelDefaultForeground ?: ollamaStatusLabel.foreground
                                ollamaStatusLabel.isVisible = true
                            },
                            onFailure = { error ->
                                ollamaStatusLabel.text =
                                    statusDotHtml(
                                        "Error: ${error.message ?: "Validation failed"}",
                                        AuthStatusKind.DISCONNECTED,
                                    )
                                ollamaStatusLabel.foreground =
                                    statusLabelDefaultForeground ?: ollamaStatusLabel.foreground
                                ollamaStatusLabel.isVisible = true
                            },
                        )
                    },
                    modality,
                )
        }
    }

    private fun setOllamaPendingStatus() {
        ollamaStatusLabel.text = statusDotHtml("Validating API key...", AuthStatusKind.PENDING)
        ollamaStatusLabel.foreground = statusLabelDefaultForeground ?: ollamaStatusLabel.foreground
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

    private companion object {
        private const val API_KEY_PLACEHOLDER = "********"
    }
}
