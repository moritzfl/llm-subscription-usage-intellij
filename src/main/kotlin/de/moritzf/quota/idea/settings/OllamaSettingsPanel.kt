package de.moritzf.quota.idea.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.quota.idea.common.QuotaUsageService
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
) : BorderLayoutPanel() {
    val ollamaHideFromPopupCheckBox = com.intellij.ui.components.JBCheckBox("Hide from quota popup")
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

    init {
        val ollamaConfigPanel = panel {
            row {
                cell(ollamaHideFromPopupCheckBox)
            }
            row {
                label("Session cookie")
            }
            row {
                cell(ollamaStatusLabel)
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
                button("Save") {
                    val sessionCookie = String(sessionCookieField.password)
                    val cfClearance = String(cfClearanceField.password).ifBlank { null }
                    if (sessionCookie.isNotBlank() && sessionCookie != SESSION_COOKIE_PLACEHOLDER) {
                        OllamaSessionCookieStore.getInstance().save(sessionCookie, cfClearance)
                        sessionCookieField.text = SESSION_COOKIE_PLACEHOLDER
                        cfClearanceField.text = if (cfClearance.isNullOrBlank()) "" else CF_PLACEHOLDER
                        setOllamaPendingStatus("Validating session cookie...")
                        validateCookieNow(sessionCookie, cfClearance)
                        QuotaUsageService.getInstance().refreshOllamaAsync()
                    }
                }
                button("Clear") {
                    OllamaSessionCookieStore.getInstance().clear()
                    sessionCookieField.text = ""
                    cfClearanceField.text = ""
                    updateOllamaStatus()
                    QuotaUsageService.getInstance().clearOllamaUsageData()
                }
            }
            separator()
            row {
                label("Extract from ollama.com → DevTools → Storage → Cookies. Paste __Secure-session (required) and cf_clearance (optional).")
            }
        }

        addToTop(ollamaConfigPanel)
        addToCenter(
            BorderLayoutPanel().apply {
                addToTop(JBLabel("Last quota response:"))
                addToCenter(createResponseViewerPanel(ollamaJsonViewer))
            },
        )
    }

    fun updateOllamaFields() {
        val (sessionCookie, cfClearance) = OllamaSessionCookieStore.getInstance().loadBlocking()
        sessionCookieField.text = if (sessionCookie.isNullOrBlank()) "" else SESSION_COOKIE_PLACEHOLDER
        cfClearanceField.text = if (cfClearance.isNullOrBlank()) "" else CF_PLACEHOLDER
        updateOllamaStatus()
    }

    fun updateOllamaStatus() {
        val cookieStore = OllamaSessionCookieStore.getInstance()
        val (sessionCookie, _) = cookieStore.loadBlocking()
        val ollamaQuota = QuotaUsageService.getInstance().getLastOllamaQuota()
        val ollamaError = QuotaUsageService.getInstance().getLastOllamaError()

        when {
            sessionCookie.isNullOrBlank() -> {
                ollamaStatusLabel.text = formatStatusText("No session cookie configured", AuthStatusKind.DISCONNECTED)
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
                ollamaStatusLabel.text = formatStatusText("Session cookie stored securely", AuthStatusKind.CONNECTED)
                ollamaStatusLabel.foreground = statusLabelDefaultForeground ?: ollamaStatusLabel.foreground
            }
        }
        ollamaStatusLabel.isVisible = true
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

    private fun setOllamaPendingStatus(text: String) {
        ollamaStatusLabel.text = formatStatusText(text, AuthStatusKind.PENDING)
        ollamaStatusLabel.foreground = statusLabelDefaultForeground ?: ollamaStatusLabel.foreground
        ollamaStatusLabel.isVisible = true
    }

    fun updateOllamaResponseArea() {
        val quota = QuotaUsageService.getInstance().getLastOllamaQuota()
        val error = QuotaUsageService.getInstance().getLastOllamaError()

        ollamaJsonViewer.text = when {
            error != null -> "Error: $error"
            quota == null -> "No Ollama response yet."
            else -> {
                try {
                    de.moritzf.quota.shared.JsonSupport.json.encodeToString(
                        de.moritzf.quota.ollama.OllamaQuota.serializer(),
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
        return "<html><span style='color:$color'>●</span>&nbsp;${QuotaUiUtil.escapeHtml(text)}</html>"
    }

    private companion object {
        private const val SESSION_COOKIE_PLACEHOLDER = "********"
        private const val CF_PLACEHOLDER = "********"
    }
}
