package de.moritzf.quota.shared

import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RealtimeSpeechResourcesTest {
    @Test
    fun partialInitializationReleasesAcquiredResourcesAndPreservesFailure() {
        val released = mutableListOf<String>()
        var module: String? = null
        var factory: String? = null
        val failure = IOException("factory initialization failed")
        val resources =
            RealtimeSpeechResources(
                { factory?.let(released::add) },
                { module?.let(released::add) },
            )
        assertSame(
            failure,
            assertFailsWith<IOException> {
                resources.initialize {
                    module = "module"
                    throw failure
                }
            },
        )
        resources.close()
        assertEquals(listOf("module"), released)
        assertTrue(resources.closed.get())
    }

    @Test
    fun failedReleasesDoNotSkipRemainingResourcesOrRepeatTeardown() {
        val released = mutableListOf<String>()
        val first = IOException("channel cleanup failed")
        val second = UnsatisfiedLinkError("factory cleanup failed")
        val resources =
            RealtimeSpeechResources(
                {
                    released += "channel"
                    throw first
                },
                { released += "peer" },
                {
                    released += "factory"
                    throw second
                },
                { released += "module" },
            )
        assertSame(first, assertFailsWith<IOException> { resources.close() })
        assertEquals(listOf(second), first.suppressed.toList())
        resources.close()
        assertEquals(listOf("channel", "peer", "factory", "module"), released)
    }

    @Test
    fun cancellationRemainsPrimaryWhenCleanupAlsoFails() {
        val cancellation = CancellationException("cancelled")
        val cleanup = IOException("cleanup failed")
        val resources = RealtimeSpeechResources({ throw cleanup })
        assertSame(
            cancellation,
            assertFailsWith<CancellationException> { resources.initialize { throw cancellation } },
        )
        assertEquals(listOf(cleanup), cancellation.suppressed.toList())
    }

    @Test
    fun usePreservesBodyFailureAndReleasesEveryResource() {
        val failure = InterruptedException("interrupted")
        val cleanup = IOException("cleanup failed")
        var released = false
        val resources = RealtimeSpeechResources({ throw cleanup }, { released = true })
        assertSame(
            failure,
            assertFailsWith<InterruptedException> { resources.use { throw failure } },
        )
        assertTrue(released)
        assertEquals(listOf(cleanup), failure.suppressed.toList())
    }
}
