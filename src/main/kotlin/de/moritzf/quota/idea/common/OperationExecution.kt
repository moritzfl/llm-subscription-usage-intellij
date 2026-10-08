package de.moritzf.quota.idea.common

import com.intellij.openapi.progress.ProcessCanceledException
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible

/** Preserves the MCP project context while making blocking provider transports interruptible. */
internal suspend fun <T> interruptibleOperation(block: suspend () -> T): T {
    val context = currentCoroutineContext().minusKey(Job).minusKey(ContinuationInterceptor)
    return runInterruptible(Dispatchers.IO) { runBlocking(context) { block() } }
}

/**
 * Provider exceptions may wrap interruption; cancellation must not trigger retries or JSON errors.
 */
internal fun Throwable.rethrowIfCancellation() {
    when (val cancellation = cancellationCause()) {
        is CancellationException -> throw cancellation
        is ProcessCanceledException -> throw cancellation
        is InterruptedException -> {
            Thread.currentThread().interrupt()
            throw CancellationException("Operation interrupted", cancellation)
        }
    }
}

internal fun Throwable.cancellationCause(): Throwable? {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var cause: Throwable? = this
    while (cause != null && seen.add(cause)) {
        when (cause) {
            is CancellationException,
            is ProcessCanceledException,
            is InterruptedException -> return cause
        }
        cause = cause.cause
    }
    return null
}
