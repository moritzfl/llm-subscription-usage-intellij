package de.moritzf.proxy.server

import io.ktor.utils.io.ClosedByteChannelException
import io.ktor.utils.io.ClosedWriteChannelException
import java.io.IOException
import java.io.UncheckedIOException
import java.net.SocketTimeoutException
import java.nio.channels.ClosedChannelException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientDisconnectsTest {
    @Test
    fun detectsBrokenPipeReportedByKtorWhileStreaming() {
        // Mirrors the real chain: flush -> ClosedByteChannelException ->
        // ClosedWriteChannelException
        // -> IOException("Broken pipe").
        val failure =
            ClosedByteChannelException(ClosedWriteChannelException(IOException("Broken pipe")))

        assertTrue(failure.isClientDisconnect())
    }

    @Test
    fun detectsDisconnectWrappedByTheSseEventLoop() {
        // ChatCompletionsHandler rewraps IOException from client writes as UncheckedIOException.
        val failure =
            UncheckedIOException(
                IOException(ClosedWriteChannelException(IOException("Broken pipe")))
            )

        assertTrue(failure.isClientDisconnect())
    }

    @Test
    fun detectsClosedChannel() {
        assertTrue(RuntimeException(ClosedChannelException()).isClientDisconnect())
    }

    @Test
    fun doesNotClassifyUpstreamTransportFailuresAsClientDisconnect() {
        // Upstream uses java.net.http, so its failures must still surface as server errors.
        assertFalse(IOException("Broken pipe").isClientDisconnect())
        assertFalse(SocketTimeoutException("read timed out").isClientDisconnect())
        assertFalse(IllegalStateException("boom").isClientDisconnect())
    }

    @Test
    fun survivesCyclicCauseChain() {
        val first = IOException("first")
        val second = IOException("second", first)
        first.initCause(second)

        assertFalse(first.isClientDisconnect())
    }
}
