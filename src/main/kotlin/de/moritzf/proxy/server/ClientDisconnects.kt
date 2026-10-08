package de.moritzf.proxy.server

import io.ktor.utils.io.ClosedByteChannelException
import io.ktor.utils.io.ClosedWriteChannelException
import java.nio.channels.ClosedChannelException

/**
 * True when the failure was caused by the client going away mid-response (agent cancelled the
 * completion, editor closed the connection, broken pipe) instead of a real server or upstream
 * fault.
 *
 * Detection is type-based on purpose: upstream calls use `java.net.http.HttpClient`, so a Ktor
 * byte-channel failure can only originate from the client-facing socket. Matching on messages such
 * as "Broken pipe" would also swallow genuine upstream transport errors, which must still be
 * reported to the client as a 5xx.
 */
fun Throwable.isClientDisconnect(): Boolean {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        if (
            current is ClosedByteChannelException ||
                current is ClosedWriteChannelException ||
                current is ClosedChannelException
        ) {
            return true
        }
        current = current.cause
        depth++
    }
    return false
}

private const val MAX_CAUSE_DEPTH = 32
