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
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.zai.ZaiApiKeyStore
import de.moritzf.quota.zai.ZaiQuotaClient
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants

/**
 * Z.ai settings tab.
 */
internal class ZaiSettingsPanel(
    private val modalityComponentProvider: () -> JComponent?,
    private val statusLabelDefaultForeground: Color? = null,
) : BorderLayoutPanel() {
    val zaiHideFromPopupCheckBox = com.intellij.ui.components.JBCheckBox("Hide from quota popup")
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
                cell(zaiHideFromPopupCheckBox)
            }
            row {
                label("Authentication")
            }
            row {
                cell(zaiStatusLabel)
            }
            row("API key:") {
                cell(apiKeyField)
                    .resizableColumn()
                    .align(AlignX.FILL)
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
            separator()
            row {
                label("Create an API key in the Z.ai console and store it here in IntelliJ Password Safe.")
            }
        }

        addToTop(configPanel)
        addToCenter(
            BorderLayoutPanel().apply {
                addToTop(JBLabel("Last quota response:"))
                addToCenter(createResponseViewerPanel(zaiJsonViewer))
            },
        )
    }

    fun updateZaiFields() {
        val apiKey = ZaiApiKeyStore.getInstance().load(onLoaded = ::refreshAfterApiKeyLoad)
        apiKeyField.text = if (apiKey.isNullOrBlank()) "" else API_KEY_PLACEHOLDER
        updateZaiStatus()
    }

    fun updateZaiStatus() {
        val apiKeyStore = ZaiApiKeyStore.getInstance()
        val apiKey = apiKeyStore.load(onLoaded = ::refreshAfterApiKeyLoad)
        val quota = QuotaUsageService.getInstance().getLastZaiQuota()
        val error = QuotaUsageService.getInstance().getLastZaiError()

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

    fun updateZaiResponseArea() {
        val quota = QuotaUsageService.getInstance().getLastZaiQuota()
        val error = QuotaUsageService.getInstance().getLastZaiError()
        val rawJson = QuotaUsageService.getInstance().getLastZaiResponseJson()

        zaiJsonViewer.text = when {
            error != null && !rawJson.isNullOrBlank() -> "Error: $error\n\n$rawJson"
            error != null -> "Error: $error"
            quota == null -> "No Z.ai response yet."
            !rawJson.isNullOrBlank() -> rawJson
            else -> {
                try {
                    de.moritzf.quota.shared.JsonSupport.json.encodeToString(
                        de.moritzf.quota.zai.ZaiQuota.serializer(),
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
            val result = runCatching { ZaiApiKeyStore.getInstance().save(apiKey) }
            ApplicationManager.getApplication().invokeLater({
                result.fold(
                    onSuccess = {
                        apiKeyField.text = API_KEY_PLACEHOLDER
                        validateApiKeyNow(apiKey)
                        QuotaUsageService.getInstance().refreshZaiAsync()
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
            ZaiApiKeyStore.getInstance().clear()
            ApplicationManager.getApplication().invokeLater({
                updateZaiStatus()
                QuotaUsageService.getInstance().clearZaiUsageData()
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
        updateZaiFields()
        updateZaiResponseArea()
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
        private const val API_KEY_PLACEHOLDER = "********"
    }
}
