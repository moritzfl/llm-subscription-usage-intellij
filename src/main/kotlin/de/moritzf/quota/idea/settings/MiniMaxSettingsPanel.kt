package de.moritzf.quota.idea.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.minimax.MiniMaxApiKeyStore
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.minimax.MiniMaxRegionPreference
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants

internal class MiniMaxSettingsPanel(
    private val modalityComponentProvider: () -> JComponent?,
    private val statusLabelDefaultForeground: Color? = null,
) : BorderLayoutPanel() {
    val miniMaxHideFromPopupCheckBox = com.intellij.ui.components.JBCheckBox("Hide from quota popup")
    val regionComboBox = ComboBox(MiniMaxRegionPreference.entries.toTypedArray())
    private val apiKeyField = JBPasswordField().apply { columns = 40 }
    private val statusLabel = JBLabel().apply { isVisible = false }
    private val responseViewer = createResponseViewer()

    init {
        addToTop(panel {
            row { cell(miniMaxHideFromPopupCheckBox) }
            row { cell(statusLabel) }
            row("Region:") { cell(regionComboBox) }
            row("API key:") { cell(apiKeyField).resizableColumn().align(AlignX.FILL) }
            row {
                button("Save") { saveKeysNow() }
                button("Clear") { clearKeysNow() }
            }
            separator()
        })
        addToCenter(BorderLayoutPanel().apply {
            addToTop(JBLabel("Last quota response:"))
            addToCenter(createResponseViewerPanel(responseViewer))
        })
    }

    fun updateMiniMaxFields() {
        val apiKey = MiniMaxApiKeyStore.getInstance().load(onLoaded = ::refreshAfterKeyLoad)
        apiKeyField.text = if (apiKey.isNullOrBlank()) "" else PLACEHOLDER
        regionComboBox.selectedItem = QuotaSettingsState.getInstance().miniMaxRegionPreference()
        updateMiniMaxStatus()
    }

    fun updateMiniMaxStatus() {
        val store = MiniMaxApiKeyStore.getInstance()
        val apiKey = store.load(onLoaded = ::refreshAfterKeyLoad)
        val quota = QuotaUsageService.getInstance().getLastMiniMaxQuota()
        val error = QuotaUsageService.getInstance().getLastMiniMaxError()
        statusLabel.text = when {
            !store.isLoaded() -> formatStatusText("Loading API keys...", AuthStatusKind.PENDING)
            apiKey.isNullOrBlank() -> formatStatusText("MiniMax API key missing", AuthStatusKind.DISCONNECTED)
            error != null -> formatStatusText("Error: $error", AuthStatusKind.DISCONNECTED)
            quota != null -> formatStatusText("Connected", AuthStatusKind.CONNECTED)
            else -> formatStatusText("API key stored securely", AuthStatusKind.CONNECTED)
        }
        statusLabel.foreground = statusLabelDefaultForeground ?: statusLabel.foreground
        statusLabel.isVisible = true
    }

    fun updateMiniMaxResponseArea() {
        val raw = QuotaUsageService.getInstance().getLastMiniMaxResponseJson()
        val error = QuotaUsageService.getInstance().getLastMiniMaxError()
        responseViewer.text = when {
            error != null && !raw.isNullOrBlank() -> "Error: $error\n\n$raw"
            error != null -> "Error: $error"
            raw.isNullOrBlank() -> "No MiniMax response yet."
            else -> raw
        }
        responseViewer.setCaretPosition(0)
    }

    private fun saveKeysNow() {
        val current = MiniMaxApiKeyStore.getInstance().load()
        val apiKey = String(apiKeyField.password).let { if (it == PLACEHOLDER) current else it }
        setPending("Saving API keys...")
        ApplicationManager.getApplication().executeOnPooledThread {
            MiniMaxApiKeyStore.getInstance().save(apiKey)
            ApplicationManager.getApplication().invokeLater({
                apiKeyField.text = if (apiKey.isNullOrBlank()) "" else PLACEHOLDER
                updateMiniMaxStatus()
                QuotaUsageService.getInstance().refreshMiniMaxAsync()
            }, ModalityState.stateForComponent(modalityComponentProvider() ?: this))
        }
    }

    private fun clearKeysNow() {
        setPending("Clearing API keys...")
        ApplicationManager.getApplication().executeOnPooledThread {
            MiniMaxApiKeyStore.getInstance().clear()
            ApplicationManager.getApplication().invokeLater({
                apiKeyField.text = ""
                updateMiniMaxStatus()
                QuotaUsageService.getInstance().clearMiniMaxUsageData()
            }, ModalityState.stateForComponent(modalityComponentProvider() ?: this))
        }
    }

    private fun refreshAfterKeyLoad() {
        updateMiniMaxFields()
        updateMiniMaxResponseArea()
    }

    private fun setPending(text: String) {
        statusLabel.text = formatStatusText(text, AuthStatusKind.PENDING)
        statusLabel.isVisible = true
    }

    private fun createResponseViewer() = com.intellij.ui.components.JBTextArea().apply {
        isEditable = false
        lineWrap = false
        wrapStyleWord = false
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
        margin = JBUI.insets(6)
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

    private companion object { const val PLACEHOLDER = "********" }
}
