package de.moritzf.quota.idea.settings

import com.intellij.util.concurrency.AppExecutorUtil
import de.moritzf.quota.idea.common.cancellationCause
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities

/** One replaceable background task; queued UI updates are tied to its generation. */
internal class TestDialogTask(
    private val executor: Executor = AppExecutorUtil.getAppExecutorService(),
    private val dispatch: (() -> Unit) -> Unit = { SwingUtilities.invokeLater(it) },
    private val ownerDisposed: () -> Boolean = { false },
) {
    private val generation = AtomicInteger()
    private var worker: FutureTask<Unit>? = null

    fun start(work: (Int) -> Unit) {
        cancel()
        val gen = generation.get()
        val task = FutureTask { work(gen) }
        worker = task
        executor.execute(task)
    }

    fun cancel() {
        generation.incrementAndGet()
        worker?.cancel(true)
        worker = null
    }

    fun isActive(gen: Int): Boolean = generation.get() == gen && !ownerDisposed()

    fun checkActive(gen: Int) {
        if (!isActive(gen) || Thread.currentThread().isInterrupted)
            throw CancellationException("Aborted")
    }

    fun onEdt(gen: Int, update: () -> Unit) {
        dispatch { if (isActive(gen)) update() }
    }

    companion object {
        fun isCancellation(failure: Throwable): Boolean = failure.cancellationCause() != null
    }
}
