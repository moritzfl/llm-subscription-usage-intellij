package de.moritzf.quota.idea.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import de.moritzf.quota.azure.AzureCli
import de.moritzf.quota.azure.AzureCliAccount
import de.moritzf.quota.azure.AzureQuota
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.ui.QuotaUiUtil
import java.awt.event.ItemEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.Timer
import javax.swing.event.DocumentEvent

internal class AzureSettingsPanel : ProviderSettingsPanel() {
    val executableField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(null, FileChooserDescriptorFactory.singleFile().withTitle("Azure CLI"))
        textField.columns = 28
        toolTipText = "Optional absolute path to az. Blank uses PATH and standard install locations."
    }
    val subscriptionField = JBTextField().apply { columns = 28 }
    val resourceField = JBTextField().apply { columns = 24 }
    val endpointField = JBTextField().apply { columns = 28 }
    val locationField = JBTextField().apply { columns = 16 }
    val deploymentsField = JBTextField().apply { columns = 28 }
    private val accountCombo = ComboBox<AzureCliAccount>()
    private val status = JBLabel()
    private val viewer = createResponseViewer()
    private var applyingFields = false
    private var suppressSelection = false
    private var refreshGeneration = 0
    private val refreshTimer = Timer(400) { refreshAccounts(interactive = false) }.apply { isRepeats = false }

    init {
        accountCombo.renderer = AzureAccountRenderer()
        accountCombo.addItemListener { event ->
            if (suppressSelection || event.stateChange != ItemEvent.SELECTED) return@addItemListener
            val selected = event.item as? AzureCliAccount ?: return@addItemListener
            if (subscriptionField.text.trim() != selected.subscriptionId) {
                subscriptionField.text = selected.subscriptionId
            }
        }
        executableField.textField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                if (!applyingFields) refreshTimer.restart()
            }
        })
        install(panel {
            row { cell(status).align(AlignX.FILL).resizableColumn() }
            row {
                comment(
                    "Uses a signed-in Azure CLI identity. Run az login in a terminal. " +
                        "Credentials stay with Azure CLI; this plugin does not read ~/.azure.",
                )
            }
            row("Azure CLI:") {
                cell(executableField).align(AlignX.FILL).resizableColumn()
                    .comment("Leave blank to find az automatically. Browse only if it is not on PATH.")
            }
            row("Subscription:") {
                cell(subscriptionField).align(AlignX.FILL).resizableColumn()
                    .comment("Blank uses the CLI default. Picking a CLI account pins that subscription.")
            }
            row("CLI accounts:") {
                cell(accountCombo).align(AlignX.FILL).resizableColumn()
                    .comment("Loaded from az account list. Refresh after az login. Your pinned subscription stays selected.")
                button("Refresh") { refreshAccounts(interactive = true) }
            }
            row("Resource:") {
                cell(resourceField).align(AlignX.FILL).resizableColumn()
                    .comment("Azure OpenAI resource name, for example my-models. Builds https://name.openai.azure.com/openai/v1.")
            }
            row("Endpoint:") {
                cell(endpointField).align(AlignX.FILL).resizableColumn()
                    .comment("Optional. Overrides the resource URL. https Azure OpenAI, Cognitive Services, or Foundry hosts only.")
            }
            row("Location:") {
                cell(locationField)
                    .comment("Optional region id, for example eastus. Quota usage is skipped when this login cannot read it.")
            }
            row("Deployments:") {
                cell(deploymentsField).align(AlignX.FILL).resizableColumn()
                    .comment("Optional deployment names. Used when discovery is unavailable. Proxy accepts az-<deployment-name> even if unlisted.")
            }
            row {
                browserLink(
                    "Azure OpenAI auth (Microsoft documentation)",
                    "https://learn.microsoft.com/en-us/azure/foundry/how-to/integrate-with-other-apps",
                )
            }
            row {
                browserLink(
                    "Quota and usages API (Microsoft documentation)",
                    "https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/quota",
                )
            }
        }, createResponseSection(viewer))
    }

    fun normalizedExecutablePath(): String? = executableField.text.trim().takeIf { it.isNotEmpty() }
    fun subscriptionId(): String? = subscriptionField.text.trim().takeIf { it.isNotEmpty() }
    fun resourceName(): String? = resourceField.text.trim().takeIf { it.isNotEmpty() }
    fun endpoint(): String? = endpointField.text.trim().takeIf { it.isNotEmpty() }
    fun locationId(): String? = locationField.text.trim().takeIf { it.isNotEmpty() }
    fun deploymentNames(): String? = deploymentsField.text.trim().takeIf { it.isNotEmpty() }

    fun differsFrom(account: ProviderAccount?): Boolean {
        return normalizedExecutablePath().orEmpty() != account?.extra(ProviderAccount.EXTRA_AZURE_EXECUTABLE).orEmpty() ||
            subscriptionId().orEmpty() != account?.extra(ProviderAccount.EXTRA_AZURE_SUBSCRIPTION).orEmpty() ||
            resourceName().orEmpty() != account?.extra(ProviderAccount.EXTRA_AZURE_RESOURCE).orEmpty() ||
            endpoint().orEmpty() != account?.extra(ProviderAccount.EXTRA_AZURE_ENDPOINT).orEmpty() ||
            locationId().orEmpty() != account?.extra(ProviderAccount.EXTRA_AZURE_LOCATION).orEmpty() ||
            deploymentNames().orEmpty() != account?.extra(ProviderAccount.EXTRA_AZURE_DEPLOYMENTS).orEmpty()
    }

    private fun refreshAccounts(interactive: Boolean) {
        refreshTimer.stop()
        val generation = ++refreshGeneration
        val path = normalizedExecutablePath()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val executable = AzureCli.findExecutable(path)
                    ?: error("Azure CLI not found.")
                AzureCli(executable).listAccounts()
            }
            ApplicationManager.getApplication().invokeLater {
                if (generation != refreshGeneration) return@invokeLater
                result.onFailure {
                    if (interactive) {
                        Messages.showWarningDialog(this, it.message ?: "Could not list Azure CLI accounts.", "Azure CLI")
                    }
                }.onSuccess { accounts ->
                    applyAccounts(accounts, subscriptionId())
                    if (interactive && accounts.isEmpty()) {
                        Messages.showWarningDialog(
                            this,
                            "az account list returned no subscriptions. Run az login, then refresh.",
                            "Azure CLI",
                        )
                    }
                }
            }
        }
    }

    private fun applyAccounts(accounts: List<AzureCliAccount>, pinnedSubscriptionId: String?) {
        val preferred = preferredAzureCliAccount(accounts, pinnedSubscriptionId)
        suppressSelection = true
        try {
            accountCombo.model = DefaultComboBoxModel(accounts.toTypedArray())
            if (preferred != null) accountCombo.selectedItem = preferred
            else accountCombo.selectedIndex = -1
        } finally {
            suppressSelection = false
        }
    }

    override fun updateFields() {
        val account = boundAccount
        applyingFields = true
        try {
            executableField.text = account?.extra(ProviderAccount.EXTRA_AZURE_EXECUTABLE).orEmpty()
            subscriptionField.text = account?.extra(ProviderAccount.EXTRA_AZURE_SUBSCRIPTION).orEmpty()
            resourceField.text = account?.extra(ProviderAccount.EXTRA_AZURE_RESOURCE).orEmpty()
            endpointField.text = account?.extra(ProviderAccount.EXTRA_AZURE_ENDPOINT).orEmpty()
            locationField.text = account?.extra(ProviderAccount.EXTRA_AZURE_LOCATION).orEmpty()
            deploymentsField.text = account?.extra(ProviderAccount.EXTRA_AZURE_DEPLOYMENTS).orEmpty()
        } finally {
            applyingFields = false
        }
        updateStatus()
        refreshAccounts(interactive = false)
    }

    override fun updateStatus() {
        val service = QuotaUsageService.getInstance()
        val id = accountKey(QuotaProviderType.AZURE)
        val quota = service.getLastQuota(id) as? AzureQuota
        val error = service.getLastError(id)
        val identity = quota?.account
        val message = when {
            error != null -> AuthStatusMessage(error, isError = true)
            quota == null -> AuthStatusMessage("No Azure reading yet.", kind = AuthStatusKind.PENDING)
            quota.warnings.isNotEmpty() -> AuthStatusMessage(
                buildString {
                    append(identity?.userName ?: "Signed in")
                    append(". ")
                    append(quota.warnings.joinToString(" "))
                },
                kind = AuthStatusKind.PENDING,
            )
            else -> AuthStatusMessage("Signed in${identity?.userName?.let { " as $it" }.orEmpty()}.")
        }
        val color = when (message.kind) {
            AuthStatusKind.CONNECTED -> "#4CAF50"
            AuthStatusKind.DISCONNECTED -> "#F44336"
            AuthStatusKind.PENDING -> "#FFC107"
        }
        status.text = "<html><span style=\"color: $color\">●</span>&nbsp;${QuotaUiUtil.escapeHtml(message.text)}</html>"
    }

    override fun updateResponseArea() {
        val service = QuotaUsageService.getInstance()
        val id = accountKey(QuotaProviderType.AZURE)
        viewer.text = service.getLastError(id) ?: service.getLastResponseJson(id) ?: "No Azure reading yet."
        viewer.caretPosition = 0
    }
}

internal fun preferredAzureCliAccount(accounts: List<AzureCliAccount>, subscriptionId: String?): AzureCliAccount? {
    val pinned = subscriptionId?.trim()?.takeIf { it.isNotEmpty() }
    if (pinned != null) return accounts.firstOrNull { it.subscriptionId.equals(pinned, ignoreCase = true) }
    return accounts.firstOrNull { it.isDefault } ?: accounts.singleOrNull()
}

private class AzureAccountRenderer : javax.swing.DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: javax.swing.JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): java.awt.Component {
        val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        val account = value as? AzureCliAccount
        text = if (account == null) {
            ""
        } else {
            val who = account.userName ?: account.userType ?: "account"
            "$who — ${account.subscriptionName}"
        }
        return component
    }
}
