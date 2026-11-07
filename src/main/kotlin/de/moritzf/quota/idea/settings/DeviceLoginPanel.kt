package de.moritzf.quota.idea.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import de.moritzf.quota.idea.auth.DeviceLoginPrompt
import de.moritzf.quota.idea.common.QuotaBundle.message
import javax.swing.JButton

/** Copyable instructions also work when the IDE cannot launch a browser. */
internal class DeviceLoginPanel {
    private val url = JBTextField().apply { isEditable = false }
    private val code = JBTextField().apply { isEditable = false }

    val component = panel {
        row { text(message("settings.login.deviceInstructions")) }
        row("URL:") {
            cell(url).align(AlignX.FILL).resizableColumn()
            cell(copyButton(message("settings.login.copyUrl"), url))
        }
        row("Code:") {
            cell(code).align(AlignX.FILL).resizableColumn()
            cell(copyButton(message("settings.login.copyCode"), code))
        }
    }
        .apply { isVisible = false }

    fun show(prompt: DeviceLoginPrompt?) {
        url.text = prompt?.verificationUrl.orEmpty()
        code.text = prompt?.userCode.orEmpty()
        component.isVisible = prompt != null
    }

    private fun copyButton(label: String, field: JBTextField) =
        JButton(label, AllIcons.Actions.Copy).apply {
            addActionListener { CopyPasteManager.copyTextToClipboard(field.text) }
        }
}
