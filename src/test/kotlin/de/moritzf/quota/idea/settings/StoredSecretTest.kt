package de.moritzf.quota.idea.settings

import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoredSecretTest {
    private val reads = ArrayDeque<Runnable>()
    private val ui = ArrayDeque<() -> Unit>()
    private val store = mutableMapOf<String, String>()
    private val field = JBPasswordField()

    private fun secret(
        fields: List<javax.swing.text.JTextComponent> = listOf(field),
        split: (String?) -> List<String> = { listOf(it.orEmpty()) },
        join: (List<String>) -> String? = { it.single().trim().ifEmpty { null } },
    ) =
        StoredSecret(
            fields,
            read = { store[it] },
            write = { id, value -> if (value == null) store.remove(id) else store[id] = value },
            split = split,
            join = join,
            executor = { reads += it },
            dispatch = { ui += it },
        )

    private fun finishReads() {
        while (reads.isNotEmpty()) reads.removeFirst().run()
        while (ui.isNotEmpty()) ui.removeFirst().invoke()
    }

    private fun apply(secret: StoredSecret, vararg ids: String) {
        secret.pendingWrites(ids.toSet()).forEach {
            it.write()
            it.onSaved()
        }
    }

    @Test
    fun aTypedKeyIsSavedOnlyByApplyAndKeptPerAccount() = onEdt {
        store["a"] = "stored-a"
        val secret = secret()
        secret.show("a")
        finishReads()
        assertEquals("stored-a", String(field.password))
        assertEquals("Stored in Password Safe.", secret.hint.text)
        assertFalse(secret.isModified(setOf("a")))

        field.text = "new-a"
        assertEquals("Saved to Password Safe when you apply.", secret.hint.text)
        secret.show("b")
        finishReads()
        assertEquals("", String(field.password))
        field.text = "new-b"
        secret.show("a")
        assertEquals("new-a", String(field.password), "the draft survives switching accounts")

        assertEquals(mapOf("a" to "stored-a"), store, "typing never writes")
        assertEquals(mapOf("a" to "new-a", "b" to "new-b"), secret.drafts())
        apply(secret, "a")
        assertEquals(mapOf("a" to "new-a"), store, "only kept accounts are written")
        assertFalse(secret.isModified("a"))
        assertTrue(secret.isModified("b"))
        assertEquals("Stored in Password Safe.", secret.hint.text)
    }

    @Test
    fun anEmptiedFieldRemovesTheSecretOnApply() = onEdt {
        store["a"] = "stored-a"
        val secret = secret()
        secret.show("a")
        finishReads()
        field.text = ""
        assertEquals("Removed from Password Safe when you apply.", secret.hint.text)
        assertEquals("", secret.override("a"), "tests run without the removed key")
        apply(secret, "a")
        assertNull(store["a"])
        assertNull(secret.override("a"))
    }

    @Test
    fun discardDropsDraftsAndRereadsTheStoredValue() = onEdt {
        store["a"] = "stored-a"
        val secret = secret()
        secret.show("a")
        finishReads()
        field.text = "cancelled"
        secret.discard()
        assertFalse(secret.isModified(setOf("a")))
        assertTrue(secret.pendingWrites(setOf("a")).isEmpty())
        secret.show("a")
        finishReads()
        assertEquals("stored-a", String(field.password))
    }

    @Test
    fun aValueTypedWhilePasswordSafeIsReadStaysTheDraft() = onEdt {
        store["a"] = "stored-a"
        val secret = secret()
        secret.show("a")
        field.text = "typed-early"
        finishReads()
        assertEquals("typed-early", String(field.password))
        assertTrue(secret.isModified("a"))
    }

    @Test
    fun aReadThatFinishesAfterApplyCannotRestoreTheOldValue() = onEdt {
        store["a"] = "stored-a"
        val secret = secret()
        secret.show("a")
        reads.removeFirst().run() // Reads "stored-a"; its UI update is still queued.
        field.text = "applied"
        apply(secret, "a")
        while (ui.isNotEmpty()) ui.removeFirst().invoke()
        assertFalse(secret.isModified("a"))
        assertEquals("applied", String(field.password))
        assertEquals("applied", store["a"])
    }

    @Test
    fun invalidInputFailsApplyBeforeAnyWrite() = onEdt {
        val name = JBTextField()
        val value = JBPasswordField()
        val secret =
            secret(
                fields = listOf(name, value),
                split = {
                    listOf(it?.substringBefore('=').orEmpty(), it?.substringAfter('=').orEmpty())
                },
                join = { (n, v) ->
                    require(n.startsWith("ory_session_")) { "Need the ory_session_* cookie." }
                    "$n=$v"
                },
            )
        secret.show("a")
        finishReads()
        name.text = "wrong"
        value.text = "x"
        val failure = assertFailsWith<IllegalArgumentException> { secret.pendingWrites(setOf("a")) }
        assertEquals("Need the ory_session_* cookie.", failure.message)
        assertTrue(store.isEmpty())
        name.text = "ory_session_1"
        apply(secret, "a")
        assertEquals("ory_session_1=x", store["a"])
    }

    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait { failure = runCatching(block).exceptionOrNull() }
        failure?.let { throw it }
    }
}
