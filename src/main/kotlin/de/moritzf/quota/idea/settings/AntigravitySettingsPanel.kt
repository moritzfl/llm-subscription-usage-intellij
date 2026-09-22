package de.moritzf.quota.idea.settings

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import de.moritzf.quota.antigravity.AntigravityQuota
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
            row { text("Uses the current Antigravity CLI login. Install AGY 1.1.11 or later and run agy in a terminal to sign in.") }
            row { text("Quota only. One CLI account; credentials stay with AGY. Change accounts in AGY, then refresh here.") }
            row("AGY executable:") { cell(executableField).align(AlignX.FILL).resizableColumn() }
            row { text("Leave blank for automatic detection. Apply settings before refreshing a changed path.") }
            row {
                button("Refresh quota") {
                    val id = accountKey(QuotaProviderType.ANTIGRAVITY)
                    if (QuotaSettingsState.getInstance().account(id) == null) {
                        status.text = "Apply settings to enable quota checks."
                    } else {
                        val service = QuotaUsageService.getInstance()
                        service.clearUsageData(id, "Reading AGY quota...")
                        status.text = "Reading AGY quota..."
                        service.refreshAsync(id, forceUpdate = true)
                    }
                }
                button("CLI setup") { BrowserUtil.browse("https://antigravity.google/docs/cli/install/") }
            }
            row { cell(status).align(AlignX.FILL).resizableColumn() }
        }, createResponseSection(viewer))
    }

    fun normalizedExecutablePath(): String? = executableField.text.trim().takeIf { it.isNotEmpty() }

    override fun updateFields() {
        executableField.text = boundAccount?.extra(ProviderAccount.EXTRA_AGY_EXECUTABLE).orEmpty()
        updateStatus()
    }

    override fun updateStatus() {
        val service = QuotaUsageService.getInstance()
        val id = accountKey(QuotaProviderType.ANTIGRAVITY)
        val quota = service.getLastQuota(id) as? AntigravityQuota
        val text = service.getLastError(id) ?: when {
            quota == null -> "Refresh to check the current AGY CLI login."
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
