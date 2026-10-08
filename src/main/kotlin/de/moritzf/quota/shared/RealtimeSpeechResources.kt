package de.moritzf.quota.shared

import java.util.concurrent.atomic.AtomicBoolean

/** Releases every acquired speech resource, preserving the original failure and teardown order. */
internal class RealtimeSpeechResources(private vararg val releases: () -> Unit) : AutoCloseable {
    val closed = AtomicBoolean()

    fun initialize(block: () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            try {
                close()
            } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var failure: Throwable? = null
        for (release in releases) {
            try {
                release()
            } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup
                else if (failure !== cleanup) failure.addSuppressed(cleanup)
            }
        }
        failure?.let { throw it }
    }
}
