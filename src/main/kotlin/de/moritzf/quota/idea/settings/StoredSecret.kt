package de.moritzf.quota.idea.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.UIUtil
import de.moritzf.quota.idea.common.QuotaBundle.message
import javax.swing.event.DocumentEvent
import javax.swing.text.JTextComponent

/** Writes [value], or clears the secret when it is null. */
internal inline fun saveOrClear(value: String?, save: (String) -> Unit, clear: () -> Unit) {
    if (value == null) clear() else save(value)
}

/** One Password Safe change collected by Apply: [write] runs off the EDT, [onSaved] on it. */
internal class SecretWrite(
    val accountId: String?,
    val write: () -> Unit,
    val onSaved: () -> Unit = {},
)

/**
 * A secret the settings dialog edits, such as an API key or a session cookie, kept like every other
 * setting: each account has its own draft, Apply writes changed drafts to Password Safe, and Cancel
 * or Reset drops them. The fields show the stored value, masked by password fields, once Password
 * Safe has been read. [hint] states what Apply will do. EDT only.
 */
internal class StoredSecret(
    private val fields: List<JTextComponent>,
    private val read: (accountId: String) -> String?,
    private val write: (accountId: String, value: String?) -> Unit,
    private val split: (String?) -> List<String> = { listOf(it.orEmpty()) },
    /** The stored form of the field texts; null removes the secret. Throws for invalid input. */
    private val join: (List<String>) -> String? = { it.single().trim().ifEmpty { null } },
    private val onChange: () -> Unit = {},
    private val executor: (Runnable) -> Unit = {
        AppExecutorUtil.getAppExecutorService().execute(it)
    },
    // A pure UI update: it must reach the modal settings dialog.
    private val dispatch: (() -> Unit) -> Unit = {
        ApplicationManager.getApplication().invokeLater(it, ModalityState.any())
    },
) {
    private class Entry {
        var stored: List<String>? = null
        var draft: List<String>? = null
        var reading = false
        var readFailed = false
        /** Bumped by each save, so a read that started earlier cannot replace the saved value. */
        var version = 0
    }

    private val entries = mutableMapOf<String, Entry>()
    private var accountId: String? = null
    private var updating = false

    val hint =
        JBLabel().apply {
            font = JBFont.small()
            foreground = UIUtil.getContextHelpForeground()
        }

    init {
        val listener =
            object : DocumentAdapter() {
                override fun textChanged(event: DocumentEvent) {
                    if (updating) return
                    val entry = accountId?.let(entries::get) ?: return
                    entry.draft = fields.map { it.text }
                    changed()
                }
            }
        fields.forEach { it.document.addDocumentListener(listener) }
    }

    /** Shows [accountId]'s draft, or its stored value once Password Safe has been read. */
    fun show(accountId: String) {
        this.accountId = accountId
        val entry = entries.getOrPut(accountId) { Entry() }
        display(entry.draft ?: entry.stored ?: blank())
        if (entry.stored == null && !entry.reading && !entry.readFailed) startRead(accountId, entry)
        changed()
    }

    private fun startRead(accountId: String, entry: Entry) {
        entry.reading = true
        val version = entry.version
        executor {
            val result = runCatching { split(read(accountId)) }
            dispatch {
                if (entries[accountId] !== entry || entry.version != version) return@dispatch
                entry.reading = false
                result.fold(
                    onSuccess = { entry.stored = it },
                    onFailure = { entry.readFailed = true },
                )
                if (this.accountId == accountId) {
                    // A value typed while Password Safe was read stays the draft.
                    if (entry.draft == null) display(entry.stored ?: blank())
                    changed()
                }
            }
        }
    }

    fun isModified(accountId: String): Boolean {
        val entry = entries[accountId] ?: return false
        val draft = entry.draft?.map(String::trim) ?: return false
        val stored = entry.stored?.map(String::trim) ?: return draft.any(String::isNotEmpty)
        return draft != stored
    }

    fun isModified(keptIds: Set<String>): Boolean =
        entries.keys.any { it in keptIds && isModified(it) }

    /**
     * The value a test should use instead of the stored one: what the field shows for the account
     * on screen, otherwise its draft; "" when that is empty, so a test never falls back to a key
     * the user removed. Null when the stored value applies.
     */
    fun override(accountId: String): String? {
        val entry = entries[accountId]
        val values =
            if (this.accountId == null || this.accountId == accountId) fields.map { it.text }
            else entry?.draft ?: return null
        val stored = entry?.stored
        if (
            entry?.draft == null &&
                stored != null &&
                values.map(String::trim) == stored.map(String::trim)
        ) {
            return null
        }
        return runCatching { join(values) }.getOrNull().orEmpty()
    }

    fun drafts(): Map<String, String> =
        entries
            .filterValues { it.draft != null }
            .keys
            .mapNotNull { id -> override(id)?.let { id to it } }
            .toMap()

    /** Writes for Apply. Throws [IllegalArgumentException] naming the first invalid draft. */
    fun pendingWrites(keptIds: Set<String>): List<SecretWrite> =
        entries
            .filterKeys { it in keptIds && isModified(it) }
            .map { (id, entry) ->
                val draft = checkNotNull(entry.draft)
                val value =
                    try {
                        join(draft)
                    } catch (failure: Exception) {
                        throw IllegalArgumentException(
                            failure.message ?: message("settings.secret.invalid"),
                            failure,
                        )
                    }
                SecretWrite(
                    id,
                    write = { write(id, value) },
                    onSaved = {
                        entry.version++
                        entry.stored = split(value)
                        entry.reading = false
                        entry.readFailed = false
                        // An edit made while Apply ran stays a draft.
                        if (entry.draft == draft) entry.draft = null
                        if (accountId == id) changed()
                    },
                )
            }

    fun discard() {
        entries.clear()
        accountId = null
        display(blank())
        hint.text = ""
    }

    private fun changed() {
        val entry = accountId?.let(entries::get)
        val stored = entry?.stored?.any { it.isNotBlank() } == true
        val draftEmpty = entry?.draft?.all(String::isBlank) == true
        hint.foreground =
            if (entry?.readFailed == true) UIUtil.getErrorForeground()
            else UIUtil.getContextHelpForeground()
        hint.text =
            when {
                entry == null -> ""
                accountId?.let(::isModified) == true && draftEmpty ->
                    message("settings.secret.remove")
                accountId?.let(::isModified) == true -> message("settings.secret.save")
                entry.readFailed -> message("settings.secret.readFailed")
                entry.reading -> message("settings.secret.loading")
                stored -> message("settings.secret.stored")
                else -> ""
            }
        onChange()
    }

    private fun display(values: List<String>) {
        updating = true
        try {
            fields.zip(values).forEach { (field, value) ->
                if (field.text != value) field.text = value
            }
        } finally {
            updating = false
        }
    }

    private fun blank() = List(fields.size) { "" }
}
