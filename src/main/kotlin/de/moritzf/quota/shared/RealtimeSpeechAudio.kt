package de.moritzf.quota.shared

import de.moritzf.quota.shared.RealtimeSpeechSession.Chunks
import de.moritzf.quota.shared.RealtimeSpeechSession.Pcm
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.abs

/** Collects finite PCM output independently of the native transport and its callback threads. */
internal fun collectRealtimeSpeechAudio(
    chunks: Chunks,
    deadline: Long,
    check: () -> Unit,
    completed: () -> Long,
    nextFrame: () -> Pcm?,
    nanoTime: () -> Long = System::nanoTime,
): Pcm {
    val result = ByteArrayOutputStream()
    val preroll = ArrayDeque<Pcm>()
    var rate = 0
    var channels = 0
    var prerollBytes = 0
    var lastSignal = 0L
    fun append(frame: Pcm) {
        if (frame.bytes.size > 8 * 1024 * 1024 - result.size()) {
            throw IOException("Realtime speech exceeds the audio size limit.")
        }
        result.writeBytes(frame.bytes)
        chunks.accept(frame)
    }
    while (nanoTime() < deadline) {
        check()
        val frame = nextFrame()
        check()
        val now = nanoTime()
        if (frame != null) {
            if (rate != 0 && (rate != frame.rate || channels != frame.channels)) {
                throw IOException("Realtime audio format changed.")
            }
            rate = frame.rate
            channels = frame.channels
            var signal = false
            for (i in 0 until frame.bytes.size - 1 step 2) {
                val sample =
                    ((frame.bytes[i].toInt() and 255) or (frame.bytes[i + 1].toInt() shl 8))
                        .toShort()
                if (abs(sample.toInt()) >= 128) {
                    signal = true
                    break
                }
            }
            if (signal) lastSignal = now
            if (result.size() == 0) {
                preroll.addLast(frame)
                prerollBytes += frame.bytes.size
                while (preroll.size > 1 && prerollBytes > rate * channels * 2 / 5) {
                    prerollBytes -= preroll.removeFirst().bytes.size
                }
                if (!signal) continue
                for (start in preroll) {
                    append(start)
                }
                preroll.clear()
            } else {
                append(frame)
            }
        }
        // RTP and sideband completion travel separately. Drain the playout tail before closing.
        if (
            completed() != 0L &&
                result.size() > 0 &&
                now - completed() > TimeUnit.SECONDS.toNanos(1) &&
                now - lastSignal > TimeUnit.MILLISECONDS.toNanos(400)
        ) {
            return Pcm(result.toByteArray(), rate, channels)
        }
    }
    throw TimeoutException("Realtime speech timed out.")
}
