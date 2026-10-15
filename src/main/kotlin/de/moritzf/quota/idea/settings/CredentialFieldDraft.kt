package de.moritzf.quota.idea.settings

import com.intellij.ui.DocumentAdapter
import javax.swing.event.DocumentEvent
import javax.swing.text.JTextComponent

/** Dialog-local edits survive account switches and delayed Password Safe reads. EDT only. */
internal class CredentialFieldDraft(private val field: JTextComponent) {
    private val drafts = mutableMapOf<String, String>()
    private var accountId: String? = null
    private var updating = false

    init {
        field.document.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(event: DocumentEvent) {
                    if (!updating) accountId?.let { drafts[it] = field.text }
                }
            }
        )
    }

    fun show(accountId: String, storedValue: String) {
        this.accountId = accountId
        val value = drafts[accountId] ?: storedValue
        if (field.text == value) return
        updating = true
        try {
            field.text = value
        } finally {
            updating = false
        }
    }

    /** A completed save must not discard an edit made after the button was clicked. */
    fun saved(accountId: String, submittedValue: String) {
        if (drafts[accountId] == submittedValue) drafts.remove(accountId)
    }

    fun snapshot(): Map<String, String> = drafts.toMap()

    fun discard() {
        drafts.clear()
        accountId = null
    }
}
