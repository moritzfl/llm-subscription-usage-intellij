package de.moritzf.quota.idea.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
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
import javax.swing.DefaultComboBoxModel

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
    private var listing = false

    init {
        accountCombo.renderer = AzureAccountRenderer()
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
                button("Detect") { detectExecutable() }
            }
            row("Subscription:") {
                cell(subscriptionField).align(AlignX.FILL).resizableColumn()
                    .comment("Blank uses the CLI default. Pin a subscription so this account stays personal.")
            }
            row("CLI accounts:") {
                cell(accountCombo).align(AlignX.FILL).resizableColumn()
                button("List") { listAccounts() }
                button("Use") { useSelectedAccount() }
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
                    .comment("Optional deployment names. Used when the model list is not readable. Proxy still accepts other names.")
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

    private fun detectExecutable() {
        val detected = AzureCli.findExecutable()
        if (detected == null) {
            Messages.showWarningDialog(this, "Could not find az on PATH or in standard install locations.", "Azure CLI Not Found")
            return
        }
        executableField.text = detected.toString()
    }

    private fun listAccounts() {
        if (listing) return
        listing = true
        val path = normalizedExecutablePath()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val executable = AzureCli.findExecutable(path)
                    ?: error("Azure CLI not found.")
                AzureCli(executable).listAccounts()
            }
            ApplicationManager.getApplication().invokeLater {
                listing = false
                if (!isDisplayable) return@invokeLater
                result.onFailure {
                    Messages.showWarningDialog(this, it.message ?: "Could not list Azure CLI accounts.", "Azure CLI")
                }.onSuccess { accounts ->
                    accountCombo.model = DefaultComboBoxModel(accounts.toTypedArray())
                    if (accounts.isEmpty()) {
                        Messages.showWarningDialog(this, "az account list returned no subscriptions. Run az login, then list again.", "Azure CLI")
                    }
                }
            }
        }
    }

    private fun useSelectedAccount() {
        val selected = accountCombo.selectedItem as? AzureCliAccount ?: return
        subscriptionField.text = selected.subscriptionId
    }

    override fun updateFields() {
        val account = boundAccount
        executableField.text = account?.extra(ProviderAccount.EXTRA_AZURE_EXECUTABLE).orEmpty()
        subscriptionField.text = account?.extra(ProviderAccount.EXTRA_AZURE_SUBSCRIPTION).orEmpty()
        resourceField.text = account?.extra(ProviderAccount.EXTRA_AZURE_RESOURCE).orEmpty()
        endpointField.text = account?.extra(ProviderAccount.EXTRA_AZURE_ENDPOINT).orEmpty()
        locationField.text = account?.extra(ProviderAccount.EXTRA_AZURE_LOCATION).orEmpty()
        deploymentsField.text = account?.extra(ProviderAccount.EXTRA_AZURE_DEPLOYMENTS).orEmpty()
        updateStatus()
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
