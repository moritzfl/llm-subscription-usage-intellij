package de.moritzf.quota.idea.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.panel
import de.moritzf.quota.idea.common.QuotaBundle.message
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.minimax.MiniMaxApiKeyStore
import de.moritzf.quota.minimax.MiniMaxQuota
import de.moritzf.quota.minimax.MiniMaxRegionPreference
import de.moritzf.quota.minimax.MiniMaxVisionClient
import java.awt.Color
import javax.swing.JComponent

internal class MiniMaxSettingsPanel(
    private val modalityComponentProvider: () -> JComponent?,
    private val statusLabelDefaultForeground: Color? = null,
) : ProviderSettingsPanel() {
    private val regionComboBox = ComboBox(MiniMaxRegionPreference.entries.toTypedArray())
    private val apiKeyField = JBPasswordField().apply { columns = 40 }
    private val apiKeyDraft = credentialDraft(apiKeyField)
    private val statusLabel = JBLabel().apply { isVisible = false }
    private val responseViewer = createResponseViewer()
    override val visionModelCombo = VisionModelCombo()

    init {
        install(
            panel {
                row { statusCell(statusLabel) }
                row("Region:") { cell(regionComboBox) }
                row("Subscription key:") { cell(apiKeyField).resizableColumn().align(AlignX.FILL) }
                row(message("settings.model.vision.label")) {
                    cell(visionModelCombo.combo)
                        .align(AlignX.FILL)
                        .resizableColumn()
                        .comment(message("settings.model.vision.help"), MAX_LINE_LENGTH_WORD_WRAP)
                    cell(
                        visionTestButton(
                            de.moritzf.quota.idea.mcp.VisionProvider.MINIMAX,
                            visionModelCombo,
                            modalityComponentProvider,
                        )
                    )
                }
                row {
                    button("Save") { saveKeysNow() }
                    button("Clear") { clearKeysNow() }
                }
            },
            createResponseSection(responseViewer),
        )
    }

    override fun writeAccountFields(account: ProviderAccount) {
        super.writeAccountFields(account)
        selectedRegion()?.let { account.setExtra(ProviderAccount.EXTRA_MINIMAX_REGION, it.name) }
    }

    override fun accountFieldsModified(
        account: ProviderAccount,
        saved: ProviderAccount?,
        state: QuotaSettingsState,
    ): Boolean =
        selectedRegion() != state.miniMaxRegionFor(account.id) ||
            super.accountFieldsModified(account, saved, state)

    private fun selectedRegion(): MiniMaxRegionPreference? =
        regionComboBox.selectedItem as? MiniMaxRegionPreference

    override fun updateFields() {
        loadCredentialField()
        visionModelCombo.show(
            boundAccount?.extra(ProviderAccount.EXTRA_VISION_MODEL),
            listOf(MiniMaxVisionClient.MODEL),
        )
        regionComboBox.selectedItem =
            boundAccount
                ?.extra(ProviderAccount.EXTRA_MINIMAX_REGION)
                ?.let(MiniMaxRegionPreference::fromStorageValue)
                ?: QuotaSettingsState.getInstance()
                    .miniMaxRegionFor(accountKey(QuotaProviderType.MINIMAX))
        updateStatus()
    }

    private fun loadCredentialField() {
        val id = accountKey(QuotaProviderType.MINIMAX)
        val isCurrent = accountUiGuard()
        val apiKey =
            MiniMaxApiKeyStore.forAccount(id)
                .load(
                    onLoaded = {
                        if (isCurrent()) {
                            loadCredentialField()
                            updateStatus()
                            updateResponseArea()
                        }
                    }
                )
        apiKeyDraft.show(id, if (apiKey.isNullOrBlank()) "" else PLACEHOLDER)
    }

    override fun testConnection(): ProviderTestConnection =
        ProviderTestConnection(
            checkNotNull(boundAccount).snapshot().apply {
                setExtra(
                    ProviderAccount.EXTRA_MINIMAX_REGION,
                    selectedRegion()?.name,
                )
            },
            String(apiKeyField.password).takeUnless { it == PLACEHOLDER },
        )

    override fun testApiKeyDrafts(): Map<String, String> =
        apiKeyDraft.snapshot().filterValues { it != PLACEHOLDER }

    override fun updateStatus() {
        val isCurrent = accountUiGuard()
        val store = MiniMaxApiKeyStore.forAccount(accountKey(QuotaProviderType.MINIMAX))
        val apiKey = store.load(onLoaded = { if (isCurrent()) updateStatus() })
        val quota =
            QuotaUsageService.getInstance().getLastQuota(accountKey(QuotaProviderType.MINIMAX))
                as? MiniMaxQuota
        val error =
            QuotaUsageService.getInstance().getLastError(accountKey(QuotaProviderType.MINIMAX))
        statusLabel.text =
            when {
                !store.isLoaded() -> statusDotHtml("Loading API keys...", AuthStatusKind.PENDING)
                apiKey.isNullOrBlank() ->
                    statusDotHtml(
                        "MiniMax subscription key missing",
                        AuthStatusKind.DISCONNECTED,
                    )
                error != null -> statusDotHtml("Error: $error", AuthStatusKind.DISCONNECTED)
                quota != null -> statusDotHtml("Connected", AuthStatusKind.CONNECTED)
                else -> statusDotHtml("API key stored securely", AuthStatusKind.CONNECTED)
            }
        statusLabel.foreground = statusLabelDefaultForeground ?: statusLabel.foreground
        statusLabel.isVisible = true
    }

    override fun updateResponseArea() {
        val raw =
            QuotaUsageService.getInstance()
                .getLastResponseJson(accountKey(QuotaProviderType.MINIMAX))
        val error =
            QuotaUsageService.getInstance().getLastError(accountKey(QuotaProviderType.MINIMAX))
        responseViewer.text =
            when {
                error != null && !raw.isNullOrBlank() -> "Error: $error\n\n$raw"
                error != null -> "Error: $error"
                raw.isNullOrBlank() -> "No MiniMax response yet."
                else -> raw
            }
        responseViewer.setCaretPosition(0)
    }

    private fun saveKeysNow() {
        val id = accountKey(QuotaProviderType.MINIMAX)
        val isCurrent = accountUiGuard()
        val modality = ModalityState.stateForComponent(modalityComponentProvider() ?: this)
        val current = MiniMaxApiKeyStore.forAccount(id).load()
        val submitted = String(apiKeyField.password)
        val apiKey = submitted.let { if (it == PLACEHOLDER) current else it }
        setPending("Saving API keys...")
        ApplicationManager.getApplication().executeOnPooledThread {
            MiniMaxApiKeyStore.forAccount(id).save(apiKey)
            QuotaUsageService.getInstance().refreshAsync(id)
            ApplicationManager.getApplication()
                .invokeLater(
                    {
                        apiKeyDraft.saved(id, submitted)
                        if (isCurrent()) {
                            apiKeyDraft.show(id, if (apiKey.isNullOrBlank()) "" else PLACEHOLDER)
                            updateStatus()
                        }
                    },
                    modality,
                )
        }
    }

    private fun clearKeysNow() {
        val id = accountKey(QuotaProviderType.MINIMAX)
        val isCurrent = accountUiGuard()
        val submitted = String(apiKeyField.password)
        val modality = ModalityState.stateForComponent(modalityComponentProvider() ?: this)
        setPending("Clearing API keys...")
        ApplicationManager.getApplication().executeOnPooledThread {
            MiniMaxApiKeyStore.forAccount(id).clear()
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

    private fun setPending(text: String) {
        statusLabel.text = statusDotHtml(text, AuthStatusKind.PENDING)
        statusLabel.isVisible = true
    }

    private companion object {
        const val PLACEHOLDER = "********"
    }
}
