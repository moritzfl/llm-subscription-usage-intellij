package de.moritzf.quota.idea.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.ollama.OllamaQuota
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.ollama.OllamaApiKeyStore
import de.moritzf.quota.idea.ollama.OllamaSessionCookieStore
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.ollama.OllamaQuotaClient
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants
import java.util.concurrent.atomic.AtomicLong

/**
 * Ollama Cloud settings tab.
 */
internal class OllamaSettingsPanel(
    private val modalityComponentProvider: () -> JComponent?,
    private val statusLabelDefaultForeground: Color? = null,
) : ProviderSettingsPanel() {
    override val hideFromPopupCheckBox = com.intellij.ui.components.JBCheckBox("Hide from quota popup")
    private val apiKeyField = JBPasswordField().apply {
        columns = 40
        toolTipText = "Ollama API key from ollama.com/settings/keys (used for MCP web search only)"
    }
    private val sessionCookieField = JBPasswordField().apply {
        columns = 40
        toolTipText = "__Secure-session cookie from ollama.com (extract from browser DevTools)"
    }
    private val cfClearanceField = JBPasswordField().apply {
        columns = 40
        toolTipText = "cf_clearance cookie from ollama.com (optional, helps bypass Cloudflare)"
    }
    private val ollamaStatusLabel = JBLabel().apply { isVisible = false }
    private val ollamaJsonViewer = createResponseViewer()
    private val validationGeneration = AtomicLong(0)
    private var awaitingCookieLoadRefresh: Boolean = false

    init {
        val ollamaConfigPanel = panel {
            row {
                cell(hideFromPopupCheckBox)
            }
            row {
                cell(ollamaStatusLabel)
            }
            row {
                label("API key enables Ollama MCP web search. Usage quota still uses the session cookie below.")
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
                label("Extract from ollama.com → DevTools → Storage → Cookies. Paste __Secure-session (required) and cf_clearance (optional).")
            }
            row("Session cookie (__Secure-session):") {
                cell(sessionCookieField)
                    .resizableColumn()
                    .align(AlignX.FILL)
            }
            row("CF clearance:") {
                cell(cfClearanceField)
                    .resizableColumn()
                    .align(AlignX.FILL)
            }
            row {
                button("Save Cookies") {
                    val sessionCookie = String(sessionCookieField.password)
                    val cfClearance = String(cfClearanceField.password).ifBlank { null }
                    if (sessionCookie.isNotBlank() && sessionCookie != SESSION_COOKIE_PLACEHOLDER) {
                        OllamaSessionCookieStore.getInstance().save(sessionCookie, cfClearance)
                        sessionCookieField.text = SESSION_COOKIE_PLACEHOLDER
                        cfClearanceField.text = if (cfClearance.isNullOrBlank()) "" else CF_PLACEHOLDER
                        setOllamaPendingStatus("Validating session cookie...")
                        validateCookieNow(sessionCookie, cfClearance)
                        QuotaUsageService.getInstance().refreshAsync(QuotaProviderType.OLLAMA)
                    }
                }
                button("Clear Cookies") {
                    OllamaSessionCookieStore.getInstance().clear()
                    sessionCookieField.text = ""
                    cfClearanceField.text = ""
                    updateStatus()
                    QuotaUsageService.getInstance().clearUsageData(QuotaProviderType.OLLAMA)
                }
            }
            separator()
        }

        addToTop(ollamaConfigPanel)
        addToCenter(
            BorderLayoutPanel().apply {
                addToTop(JBLabel("Last quota response:"))
                addToCenter(createResponseViewerPanel(ollamaJsonViewer))
            },
        )
    }

    override fun updateFields() {
        val apiKeyStore = OllamaApiKeyStore.getInstance()
        val cookieStore = OllamaSessionCookieStore.getInstance()
        val apiKey = apiKeyStore.load(onLoaded = ::refreshAfterCredentialLoad)
        val sessionCookie = cookieStore.loadSessionCookie(onLoaded = ::refreshAfterCookieLoad)
        apiKeyField.text = if (apiKey.isNullOrBlank()) "" else API_KEY_PLACEHOLDER
        sessionCookieField.text = if (sessionCookie.isNullOrBlank()) "" else SESSION_COOKIE_PLACEHOLDER
        cfClearanceField.text = if (sessionCookie.isNullOrBlank()) "" else CF_PLACEHOLDER
        updateStatus()
    }

    override fun updateStatus() {
        val apiKeyStore = OllamaApiKeyStore.getInstance()
        val cookieStore = OllamaSessionCookieStore.getInstance()
        val apiKey = apiKeyStore.load(onLoaded = ::refreshAfterCredentialLoad)
        val sessionCookie = cookieStore.loadSessionCookie(onLoaded = ::refreshAfterCookieLoad)
        val ollamaQuota = QuotaUsageService.getInstance().getLastQuota(QuotaProviderType.OLLAMA) as? OllamaQuota
        val ollamaError = QuotaUsageService.getInstance().getLastError(QuotaProviderType.OLLAMA)

        when {
            !apiKeyStore.isLoaded() || !cookieStore.isLoaded() -> {
                ollamaStatusLabel.text = formatStatusText("Loading Ollama credentials...", AuthStatusKind.PENDING)
                ollamaStatusLabel.foreground = statusLabelDefaultForeground ?: ollamaStatusLabel.foreground
            }
            sessionCookie.isNullOrBlank() && apiKey.isNullOrBlank() -> {
                ollamaStatusLabel.text = formatStatusText("No session cookie configured for quota; no API key configured for web search", AuthStatusKind.DISCONNECTED)
                ollamaStatusLabel.foreground = statusLabelDefaultForeground ?: ollamaStatusLabel.foreground
            }
            sessionCookie.isNullOrBlank() -> {
                ollamaStatusLabel.text = formatStatusText("API key stored for web search; no session cookie configured for quota", AuthStatusKind.CONNECTED)
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
                val text = if (apiKey.isNullOrBlank()) {
                    "Session cookie stored securely"
                } else {
                    "Session cookie and API key stored securely"
                }
                ollamaStatusLabel.text = formatStatusText(text, AuthStatusKind.CONNECTED)
                ollamaStatusLabel.foreground = statusLabelDefaultForeground ?: ollamaStatusLabel.foreground
            }
        }
        ollamaStatusLabel.isVisible = true
    }

    private fun saveApiKeyNow() {
        val apiKey = String(apiKeyField.password).trim()
        if (apiKey.isNotBlank() && apiKey != API_KEY_PLACEHOLDER) {
            OllamaApiKeyStore.getInstance().save(apiKey)
            apiKeyField.text = API_KEY_PLACEHOLDER
            updateStatus()
        }
    }

    private fun clearApiKeyNow() {
        OllamaApiKeyStore.getInstance().clear()
        apiKeyField.text = ""
        updateStatus()
    }

    private fun validateCookieNow(sessionCookie: String, cfClearance: String?) {
        val generation = validationGeneration.incrementAndGet()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { OllamaQuotaClient().fetchQuota(sessionCookie, cfClearance) }
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
                        ollamaStatusLabel.text = formatStatusText("Error: ${error.message ?: "Validation failed"}", AuthStatusKind.DISCONNECTED)
                        ollamaStatusLabel.foreground = statusLabelDefaultForeground ?: ollamaStatusLabel.foreground
                        ollamaStatusLabel.isVisible = true
                    },
                )
            }, ModalityState.stateForComponent(modalityComponentProvider() ?: this@OllamaSettingsPanel))
        }
    }

    private fun refreshAfterCookieLoad() {
        if (awaitingCookieLoadRefresh) {
            awaitingCookieLoadRefresh = false
            return
        }
        updateFields()
        updateResponseArea()
    }

    private fun refreshAfterCredentialLoad() {
        updateFields()
        updateResponseArea()
    }

    private fun setOllamaPendingStatus(text: String) {
        ollamaStatusLabel.text = formatStatusText(text, AuthStatusKind.PENDING)
        ollamaStatusLabel.foreground = statusLabelDefaultForeground ?: ollamaStatusLabel.foreground
        ollamaStatusLabel.isVisible = true
    }

    override fun updateResponseArea() {
        val quota = QuotaUsageService.getInstance().getLastQuota(QuotaProviderType.OLLAMA) as? OllamaQuota
        val error = QuotaUsageService.getInstance().getLastError(QuotaProviderType.OLLAMA)
        val rawJson = QuotaUsageService.getInstance().getLastResponseJson(QuotaProviderType.OLLAMA)

        ollamaJsonViewer.text = when {
            error != null && !rawJson.isNullOrBlank() -> "Error: $error\n\n$rawJson"
            error != null -> "Error: $error"
            quota == null -> "No Ollama response yet."
            !rawJson.isNullOrBlank() -> rawJson
            else -> {
                try {
                    de.moritzf.quota.shared.JsonSupport.json.encodeToString(
                        OllamaQuota.serializer(),
                        quota,
                    )
                } catch (exception: Exception) {
                    "Could not serialize response: ${exception.message}"
                }
            }
        }
        ollamaJsonViewer.setCaretPosition(0)
    }

    private fun createResponseViewer(): com.intellij.ui.components.JBTextArea {
        return com.intellij.ui.components.JBTextArea().apply {
            isEditable = false
            lineWrap = false
            wrapStyleWord = false
            font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
            margin = JBUI.insets(6)
        }
    }

    private fun createResponseViewerPanel(viewer: com.intellij.ui.components.JBTextArea): JComponent {
        return JScrollPane(viewer).apply {
            preferredSize = Dimension(1, JBUI.scale(220))
            minimumSize = Dimension(1, JBUI.scale(120))
            border = JBUI.Borders.emptyTop(4)
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }
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
        private const val SESSION_COOKIE_PLACEHOLDER = "********"
        private const val CF_PLACEHOLDER = "********"
    }
}
