package de.moritzf.quota.idea.settings

import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TestDialogTaskTest {
    @Test
    fun retryAndCloseDiscardAlreadyQueuedUiUpdates() {
        val ui = mutableListOf<() -> Unit>()
        val results = mutableListOf<String>()
        var disposed = false
        val task = TestDialogTask(Executor { it.run() }, { ui += it }, { disposed })
        task.start { gen -> task.onEdt(gen) { results += "old" } }
        task.start { gen -> task.onEdt(gen) { results += "current" } }
        ui.forEach { it() }
        assertEquals(listOf("current"), results)
        ui.clear()
        task.start { gen -> task.onEdt(gen) { results += "closed" } }
        disposed = true
        ui.forEach { it() }
        assertEquals(listOf("current"), results)
    }

    @Test
    fun abortInvalidatesWorkerAndPendingResult() {
        val ui = mutableListOf<() -> Unit>()
        var generation = -1
        val task = TestDialogTask(Executor { it.run() }, { ui += it })
        task.start { gen ->
            generation = gen
            task.onEdt(gen) { error("Stale result delivered") }
        }
        task.cancel()
        ui.forEach { it() }
        assertFailsWith<java.util.concurrent.CancellationException> { task.checkActive(generation) }
    }
}
