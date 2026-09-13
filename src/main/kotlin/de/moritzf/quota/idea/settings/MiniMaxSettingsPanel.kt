package de.moritzf.quota.idea.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.minimax.MiniMaxQuota
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.minimax.MiniMaxApiKeyStore
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.minimax.MiniMaxRegionPreference
import java.awt.Color
import javax.swing.JComponent

internal class MiniMaxSettingsPanel(
    private val modalityComponentProvider: () -> JComponent?,
    private val statusLabelDefaultForeground: Color? = null,
) : ProviderSettingsPanel() {
    val regionComboBox = ComboBox(MiniMaxRegionPreference.entries.toTypedArray())
    private val apiKeyField = JBPasswordField().apply { columns = 40 }
    private val statusLabel = JBLabel().apply { isVisible = false }
    private val responseViewer = createResponseViewer()

    init {
        install(panel {
            row { cell(statusLabel) }
            row("Region:") { cell(regionComboBox) }
            row("Subscription key:") { cell(apiKeyField).resizableColumn().align(AlignX.FILL) }
            row {
                button("Save") { saveKeysNow() }
                button("Clear") { clearKeysNow() }
            }
        }, createResponseSection(responseViewer))
    }

    override fun updateFields() {
        val apiKey = MiniMaxApiKeyStore.forAccount(accountKey(QuotaProviderType.MINIMAX)).load(onLoaded = ::refreshAfterKeyLoad)
        apiKeyField.text = if (apiKey.isNullOrBlank()) "" else PLACEHOLDER
        regionComboBox.selectedItem = boundAccount?.extra(ProviderAccount.EXTRA_MINIMAX_REGION)
            ?.let(MiniMaxRegionPreference::fromStorageValue)
            ?: QuotaSettingsState.getInstance().miniMaxRegionFor(accountKey(QuotaProviderType.MINIMAX))
        updateStatus()
    }

    override fun updateStatus() {
        val store = MiniMaxApiKeyStore.forAccount(accountKey(QuotaProviderType.MINIMAX))
        val apiKey = store.load(onLoaded = ::refreshAfterKeyLoad)
        val quota = QuotaUsageService.getInstance().getLastQuota(accountKey(QuotaProviderType.MINIMAX)) as? MiniMaxQuota
        val error = QuotaUsageService.getInstance().getLastError(accountKey(QuotaProviderType.MINIMAX))
        statusLabel.text = when {
            !store.isLoaded() -> formatStatusText("Loading API keys...", AuthStatusKind.PENDING)
            apiKey.isNullOrBlank() -> formatStatusText("MiniMax subscription key missing", AuthStatusKind.DISCONNECTED)
            error != null -> formatStatusText("Error: $error", AuthStatusKind.DISCONNECTED)
            quota != null -> formatStatusText("Connected", AuthStatusKind.CONNECTED)
            else -> formatStatusText("API key stored securely", AuthStatusKind.CONNECTED)
        }
        statusLabel.foreground = statusLabelDefaultForeground ?: statusLabel.foreground
        statusLabel.isVisible = true
    }

    override fun updateResponseArea() {
        val raw = QuotaUsageService.getInstance().getLastResponseJson(accountKey(QuotaProviderType.MINIMAX))
        val error = QuotaUsageService.getInstance().getLastError(accountKey(QuotaProviderType.MINIMAX))
        responseViewer.text = when {
            error != null && !raw.isNullOrBlank() -> "Error: $error\n\n$raw"
            error != null -> "Error: $error"
            raw.isNullOrBlank() -> "No MiniMax response yet."
            else -> raw
        }
        responseViewer.setCaretPosition(0)
    }

    private fun saveKeysNow() {
        val current = MiniMaxApiKeyStore.forAccount(accountKey(QuotaProviderType.MINIMAX)).load()
        val apiKey = String(apiKeyField.password).let { if (it == PLACEHOLDER) current else it }
        setPending("Saving API keys...")
        ApplicationManager.getApplication().executeOnPooledThread {
            MiniMaxApiKeyStore.forAccount(accountKey(QuotaProviderType.MINIMAX)).save(apiKey)
            ApplicationManager.getApplication().invokeLater({
                apiKeyField.text = if (apiKey.isNullOrBlank()) "" else PLACEHOLDER
                updateStatus()
                QuotaUsageService.getInstance().refreshAsync(accountKey(QuotaProviderType.MINIMAX))
            }, ModalityState.stateForComponent(modalityComponentProvider() ?: this))
        }
    }

    private fun clearKeysNow() {
        setPending("Clearing API keys...")
        ApplicationManager.getApplication().executeOnPooledThread {
            MiniMaxApiKeyStore.forAccount(accountKey(QuotaProviderType.MINIMAX)).clear()
            ApplicationManager.getApplication().invokeLater({
                apiKeyField.text = ""
                updateStatus()
                QuotaUsageService.getInstance().clearUsageData(accountKey(QuotaProviderType.MINIMAX))
            }, ModalityState.stateForComponent(modalityComponentProvider() ?: this))
        }
    }

    private fun refreshAfterKeyLoad() {
        updateFields()
        updateResponseArea()
    }

    private fun setPending(text: String) {
        statusLabel.text = formatStatusText(text, AuthStatusKind.PENDING)
        statusLabel.isVisible = true
    }



    private fun formatStatusText(text: String, kind: AuthStatusKind): String {
        val color = when (kind) {
            AuthStatusKind.CONNECTED -> "#4CAF50"
            AuthStatusKind.DISCONNECTED -> "#F44336"
            AuthStatusKind.PENDING -> "#FFC107"
        }
        return "<html><span style=\"color: $color\">●</span>&nbsp;${QuotaUiUtil.escapeHtml(text)}</html>"
    }

    private companion object { const val PLACEHOLDER = "********" }
}
