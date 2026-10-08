package de.moritzf.quota.idea.settings

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

internal class OpenAiPersonalTokenDialog(parent: JComponent) : DialogWrapper(parent, true) {
    private val tokenField = JBPasswordField().apply { columns = 40 }

    init {
        title = "Connect with Codex Access Token"
        setOKButtonText("Connect")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { text("For ChatGPT Business and Enterprise workspaces.") }
        row {
            text(
                "Create a personal access token in <a href=\"https://chatgpt.com/admin/access-tokens\">ChatGPT workspace settings</a>."
            )
        }
        row { text("Select the Codex scope when offered.") }
        row("Access token:") {
            cell(tokenField)
                .align(AlignX.FILL)
                .resizableColumn()
                .comment(
                    "Starts with at-. Stored in IntelliJ Password Safe. Replace it when it expires or is revoked."
                )
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = tokenField

    override fun doValidate(): ValidationInfo? {
        val password = tokenField.password
        return try {
            if (password.isEmpty())
                ValidationInfo("Enter a Codex personal access token.", tokenField)
            else null
        } finally {
            password.fill('\u0000')
        }
    }

    fun takeToken(): String {
        val password = tokenField.password
        return try {
            String(password)
        } finally {
            password.fill('\u0000')
            tokenField.text = ""
        }
    }

    override fun doCancelAction() {
        tokenField.text = ""
        super.doCancelAction()
    }
}
