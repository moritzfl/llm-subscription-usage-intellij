package de.moritzf.proxy.server

import io.ktor.util.AttributeKey

object AccessLogFields {
    val REQUEST_ID = AttributeKey<String>("requestId")
    val START_NANOS = AttributeKey<Long>("accessLogStartNanos")
    val MODE = AttributeKey<String>("accessLogMode")
    val UPSTREAM_STATUS = AttributeKey<Int>("accessLogUpstreamStatus")
    val RESPONSE_BYTES = AttributeKey<Long>("accessLogResponseBytes")

    fun mode(ctx: ProxyCall, mode: String) {
        ctx.setAttribute(MODE, mode)
    }

    fun upstreamStatus(ctx: ProxyCall, status: Int) {
        ctx.setAttribute(UPSTREAM_STATUS, status)
    }

    fun responseBytes(ctx: ProxyCall, bytes: Long) {
        ctx.setAttribute(RESPONSE_BYTES, bytes.coerceAtLeast(0L))
    }

    fun addResponseBytes(ctx: ProxyCall, bytes: Long) {
        val current = ctx.getAttribute(RESPONSE_BYTES)
        responseBytes(ctx, (current ?: 0L) + bytes.coerceAtLeast(0L))
    }
}
