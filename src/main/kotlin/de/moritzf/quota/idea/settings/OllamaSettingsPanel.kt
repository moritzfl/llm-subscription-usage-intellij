package de.moritzf.quota.idea.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.ollama.OllamaApiKeyStore
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.ollama.OllamaQuota
import de.moritzf.quota.ollama.OllamaQuotaClient
import de.moritzf.quota.ollama.OllamaResetSchedule
import de.moritzf.quota.shared.JsonSupport
import java.awt.Color
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JComponent

/**
 * Ollama Cloud settings tab — API key only (quota, web search, and proxy).
 */
internal class OllamaSettingsPanel(
    private val modalityComponentProvider: () -> JComponent?,
    private val statusLabelDefaultForeground: Color? = null,
) : ProviderSettingsPanel() {
    val monthlyResetField = JBTextField().apply {
        columns = 40
        toolTipText = "Optional ISO-8601 UTC reset from ollama.com/settings data-time, e.g. 2026-10-02T18:08:50Z"
    }
    private val apiKeyField = JBPasswordField().apply {
        columns = 40
        toolTipText = "Ollama API key from ollama.com/settings/keys"
    }
    private val ollamaStatusLabel = JBLabel().apply { isVisible = false }
    private val ollamaJsonViewer = createResponseViewer()
    private val validationGeneration = AtomicLong(0)

    init {
        val ollamaConfigPanel = panel {
            row {
                cell(ollamaStatusLabel)
            }
            row {
                text("Create an API key at ollama.com/settings/keys. Used for quota, MCP web search, and the local proxy.")
            }
            row("API key:") {
                cell(apiKeyField)
                    .resizableColumn()
                    .align(AlignX.FILL)
            }
            row("Monthly reset:") {
                cell(monthlyResetField)
                    .resizableColumn()
                    .align(AlignX.FILL)
                    .comment(
                        "Optional. Paste one ISO-8601 UTC time from ollama.com/settings (the Monthly usage data-time). " +
                            "Used only on monthly credit plans. Same day and clock each calendar month.",
                    )
            }
            row {
                button("Save API Key") {
                    saveApiKeyNow()
                }
                button("Clear API Key") {
                    clearApiKeyNow()
                }
            }
        }

        install(ollamaConfigPanel, createResponseSection(ollamaJsonViewer))
    }

    fun normalizedMonthlyResetForStorage(): String? {
        val raw = monthlyResetField.text.trim()
        if (raw.isEmpty()) return null
        return OllamaResetSchedule.parseMonthlyAnchor(raw)?.toString()
            ?: throw ConfigurationException(
                "Ollama monthly reset must be ISO-8601 UTC (for example 2026-10-02T18:08:50Z from ollama.com/settings data-time).",
            )
    }

    override fun updateFields() {
        val id = accountKey(QuotaProviderType.OLLAMA)
        val apiKeyStore = OllamaApiKeyStore.forAccount(id)
        val apiKey = apiKeyStore.load(onLoaded = { if (accountKey(QuotaProviderType.OLLAMA) == id) refreshAfterCredentialLoad() })
        apiKeyField.text = if (apiKey.isNullOrBlank()) "" else API_KEY_PLACEHOLDER
        monthlyResetField.text = boundAccount?.extra(ProviderAccount.EXTRA_OLLAMA_MONTHLY_RESET).orEmpty()
        updateStatus()
    }

    override fun updateStatus() {
        val apiKeyStore = OllamaApiKeyStore.forAccount(accountKey(QuotaProviderType.OLLAMA))
        val apiKey = apiKeyStore.load(onLoaded = ::refreshAfterCredentialLoad)
        val ollamaQuota = QuotaUsageService.getInstance().getLastQuota(accountKey(QuotaProviderType.OLLAMA)) as? OllamaQuota
        val ollamaError = QuotaUsageService.getInstance().getLastError(accountKey(QuotaProviderType.OLLAMA))

        when {
            !apiKeyStore.isLoaded() -> {
                ollamaStatusLabel.text = formatStatusText("Loading Ollama credentials...", AuthStatusKind.PENDING)
                ollamaStatusLabel.foreground = statusLabelDefaultForeground ?: ollamaStatusLabel.foreground
            }
            apiKey.isNullOrBlank() -> {
                ollamaStatusLabel.text = formatStatusText("No API key configured", AuthStatusKind.DISCONNECTED)
                ollamaStatusLabel.foreground = statusLabelDefaultForeground ?: ollamaStatusLabel.foreground
            }
            ollamaError != null -> {
                ollamaStatusLabel.text = formatStatusText("Error: $ollamaError", AuthStatusKind.DISCONNECTED)
                ollamaStatusLabel.foreground = statusLabelDefaultForeground ?: ollamaStatusLabel.foreground
            }
            ollamaQuota != null -> {
                ollamaStatusLabel.text = formatStatusText("Connected", AuthStatusKind.CONNECTED)
                ollamaStatusLabel.foreground = statusLabelDefaultForeground ?: ollamaStatusLabel.foreground
            }
            else -> {
                ollamaStatusLabel.text = formatStatusText("API key stored securely", AuthStatusKind.CONNECTED)
                ollamaStatusLabel.foreground = statusLabelDefaultForeground ?: ollamaStatusLabel.foreground
            }
        }
        ollamaStatusLabel.isVisible = true
    }

    private fun saveApiKeyNow() {
        val apiKey = String(apiKeyField.password).trim()
        if (apiKey.isNotBlank() && apiKey != API_KEY_PLACEHOLDER) {
            OllamaApiKeyStore.forAccount(accountKey(QuotaProviderType.OLLAMA)).save(apiKey)
            apiKeyField.text = API_KEY_PLACEHOLDER
            setOllamaPendingStatus()
            validateApiKeyNow(apiKey)
            QuotaUsageService.getInstance().refreshAsync(accountKey(QuotaProviderType.OLLAMA))
        }
    }

    private fun clearApiKeyNow() {
        OllamaApiKeyStore.forAccount(accountKey(QuotaProviderType.OLLAMA)).clear()
        apiKeyField.text = ""
        updateStatus()
        QuotaUsageService.getInstance().clearUsageData(accountKey(QuotaProviderType.OLLAMA))
    }

    private fun validateApiKeyNow(apiKey: String) {
        val generation = validationGeneration.incrementAndGet()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { OllamaQuotaClient().fetchQuota(apiKey) }
            ApplicationManager.getApplication().invokeLater({
                if (generation != validationGeneration.get()) {
                    return@invokeLater
                }
                result.fold(
                    onSuccess = {
                        ollamaStatusLabel.text = formatStatusText("Connected", AuthStatusKind.CONNECTED)
                        ollamaStatusLabel.foreground = statusLabelDefaultForeground ?: ollamaStatusLabel.foreground
                        ollamaStatusLabel.isVisible = true
                    },
                    onFailure = { error ->
                        ollamaStatusLabel.text = formatStatusText(
                            "Error: ${error.message ?: "Validation failed"}",
                            AuthStatusKind.DISCONNECTED,
                        )
                        ollamaStatusLabel.foreground = statusLabelDefaultForeground ?: ollamaStatusLabel.foreground
                        ollamaStatusLabel.isVisible = true
                    },
                )
            }, ModalityState.stateForComponent(modalityComponentProvider() ?: this@OllamaSettingsPanel))
        }
    }

    private fun refreshAfterCredentialLoad() {
        updateFields()
        updateResponseArea()
    }

    private fun setOllamaPendingStatus() {
        ollamaStatusLabel.text = formatStatusText("Validating API key...", AuthStatusKind.PENDING)
        ollamaStatusLabel.foreground = statusLabelDefaultForeground ?: ollamaStatusLabel.foreground
        ollamaStatusLabel.isVisible = true
    }

    override fun updateResponseArea() {
        val quota = QuotaUsageService.getInstance().getLastQuota(accountKey(QuotaProviderType.OLLAMA)) as? OllamaQuota
        val error = QuotaUsageService.getInstance().getLastError(accountKey(QuotaProviderType.OLLAMA))
        val rawJson = QuotaUsageService.getInstance().getLastResponseJson(accountKey(QuotaProviderType.OLLAMA))

        ollamaJsonViewer.text = when {
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
