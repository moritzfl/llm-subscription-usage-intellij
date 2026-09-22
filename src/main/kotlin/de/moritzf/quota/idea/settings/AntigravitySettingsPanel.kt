package de.moritzf.quota.idea.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import de.moritzf.quota.antigravity.AntigravityQuota
import de.moritzf.quota.antigravity.AntigravityQuotaClient
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.ui.QuotaUiUtil

internal class AntigravitySettingsPanel : ProviderSettingsPanel() {
    val executableField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(null, FileChooserDescriptorFactory.singleFile().withTitle("AGY Executable"))
        textField.columns = 30
        toolTipText = "Optional absolute path to agy (agy.exe on Windows). Blank uses PATH and standard install locations."
    }
    private val status = JBLabel()
    private val viewer = createResponseViewer()

    init {
        install(panel {
            row { comment("Uses the current Antigravity CLI login. Install AGY 1.1.11 or later and run agy in a terminal to sign in.") }
            row { comment("Quota only. One CLI account; credentials stay with AGY. Change accounts in AGY.") }
            row("AGY executable:") {
                cell(executableField).align(AlignX.FILL).resizableColumn()
                    .comment("Leave blank for automatic detection. Changes take effect after Apply.")
                button("Detect") { detectExecutable() }.applyToComponent {
                    toolTipText = "Auto-detect AGY from PATH and standard install locations and fill the path"
                    accessibleContext.accessibleName = "Detect AGY executable path"
                }
            }
            row {
                browserLink("AGY setup (Google documentation)", "https://antigravity.google/docs/cli/install/")
            }
            row { cell(status).align(AlignX.FILL).resizableColumn() }
        }, createResponseSection(viewer))
    }

    fun normalizedExecutablePath(): String? = executableField.text.trim().takeIf { it.isNotEmpty() }

    private fun detectExecutable() {
        val detected = AntigravityQuotaClient.findExecutable()
        if (detected == null) {
            Messages.showWarningDialog(
                this,
                "Could not find AGY on PATH or in standard install locations.",
                "AGY Path Not Found",
            )
            return
        }
        executableField.text = detected.toString()
    }

    override fun updateFields() {
        executableField.text = boundAccount?.extra(ProviderAccount.EXTRA_AGY_EXECUTABLE).orEmpty()
        updateStatus()
    }

    override fun updateStatus() {
        val service = QuotaUsageService.getInstance()
        val id = accountKey(QuotaProviderType.ANTIGRAVITY)
        val quota = service.getLastQuota(id) as? AntigravityQuota
        val text = service.getLastError(id) ?: when {
            quota == null -> "No AGY usage report yet."
            quota.warnings.isNotEmpty() -> "Connected. ${quota.warnings.joinToString(" ")}"
            else -> "Connected to the current AGY CLI account."
        }
        status.text = "<html>${QuotaUiUtil.escapeHtml(text)}</html>"
    }

    override fun updateResponseArea() {
        val service = QuotaUsageService.getInstance()
        val id = accountKey(QuotaProviderType.ANTIGRAVITY)
        viewer.text = service.getLastError(id) ?: service.getLastResponseJson(id) ?: "No AGY usage report yet."
        viewer.caretPosition = 0
    }
}
