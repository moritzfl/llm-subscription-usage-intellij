package de.moritzf.quota.shared

import de.moritzf.quota.shared.RealtimeSpeechSession.Pcm
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler

@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeSpeechAudioTest {
    @Test
    fun trimsLeadingSilenceKeepsPrerollAndStreamsExactlyReturnedAudio() {
        val silence = frame()
        val speech = frame(signal = 256)
        val fixture = Fixture(List(6) { silence } + speech + silence)
        fixture.completedAt = 350
        val result = fixture.collect()
        assertEquals(8000, result.rate)
        assertEquals(1, result.channels)
        assertEquals(listOf(silence, speech, silence), fixture.delivered)
        assertContentEquals(silence.bytes + speech.bytes + silence.bytes, result.bytes)
        assertEquals(1400, fixture.time.currentTime)
    }

    @Test
    fun completionBeforeRtpStillDrainsLateSpeechAndQuietTail() {
        val fixture = Fixture(List(19) { null } + frame(signal = -32768))
        fixture.completedAt = 50
        val result = fixture.collect()
        assertTrue(result.bytes.isNotEmpty())
        // Completion alone would allow returning at 1100 ms; late signal extends this to 1450 ms.
        assertEquals(1450, fixture.time.currentTime)
    }

    @Test
    fun silenceOrMissingCompletionTimesOut() {
        for (frames in listOf(listOf(frame()), listOf(frame(signal = 256)))) {
            val fixture = Fixture(frames)
            if (frames.first().bytes.all { it == 0.toByte() }) fixture.completedAt = 50
            assertFailsWith<TimeoutException> { fixture.collect() }
            assertEquals(2000, fixture.time.currentTime)
        }
    }

    @Test
    fun rejectsFormatChangesBeforeDeliveringChangedFrame() {
        val first = frame(signal = 256)
        val fixture = Fixture(listOf(first, Pcm(first.bytes, 16000, 1)))
        assertEquals(
            "Realtime audio format changed.",
            assertFailsWith<IOException> { fixture.collect() }.message,
        )
        assertEquals(listOf(first), fixture.delivered)
    }

    @Test
    fun enforcesSizeLimitForFirstFrameAndAccumulatedAudio() {
        val oversized = Fixture(listOf(frame(signal = 256, size = 8 * 1024 * 1024 + 2)))
        assertFailsWith<IOException> { oversized.collect() }
        assertTrue(oversized.delivered.isEmpty())
        val fixture = Fixture(List(9) { frame(signal = 256, size = 1024 * 1024) })
        assertFailsWith<IOException> { fixture.collect() }
        assertEquals(8, fixture.delivered.size)
    }

    @Test
    fun consumerCancellationAndPollingInterruptionPropagateUnchanged() {
        val cancelled = CancellationException("consumer cancelled")
        val fixture = Fixture(listOf(frame(signal = 256)))
        assertSame(
            cancelled,
            assertFailsWith<CancellationException> {
                fixture.collect(consume = { throw cancelled })
            },
        )
        val interrupted = InterruptedException("poll interrupted")
        assertSame(
            interrupted,
            assertFailsWith<InterruptedException> { fixture.collect(poll = { throw interrupted }) },
        )
    }

    @Test
    fun failureArrivingDuringPollIsNotReportedAsSuccessfulCompletion() {
        val failure = IOException("transport failed")
        var failed = false
        val fixture = Fixture(emptyList())
        assertSame(
            failure,
            assertFailsWith<IOException> {
                fixture.collect(
                    check = { if (failed) throw failure },
                    poll = {
                        failed = true
                        frame(signal = 256)
                    },
                )
            },
        )
        assertTrue(fixture.delivered.isEmpty())
    }

    private class Fixture(frames: List<Pcm?>) {
        val time = TestCoroutineScheduler()
        val delivered = mutableListOf<Pcm>()
        var completedAt: Long? = null
        private val pending = ArrayDeque(frames)

        private fun now() = TimeUnit.MILLISECONDS.toNanos(time.currentTime) + 1

        fun collect(
            check: () -> Unit = {},
            consume: (Pcm) -> Unit = { delivered += it },
            poll: () -> Pcm? = { pending.removeFirstOrNull() },
        ): Pcm =
            collectRealtimeSpeechAudio(
                chunks = consume,
                deadline = TimeUnit.SECONDS.toNanos(2) + 1,
                check = check,
                completed = {
                    completedAt
                        ?.takeIf { time.currentTime >= it }
                        ?.let { TimeUnit.MILLISECONDS.toNanos(it) + 1 } ?: 0
                },
                nextFrame = {
                    time.advanceTimeBy(50)
                    poll()
                },
                nanoTime = ::now,
            )
    }

    private fun frame(signal: Int = 0, size: Int = 1600): Pcm {
        val bytes = ByteArray(size)
        bytes[0] = signal.toByte()
        bytes[1] = (signal shr 8).toByte()
        return Pcm(bytes, 8000, 1)
    }
}
