package de.moritzf.proxy.server

import io.javalin.http.Context

object AccessLogFields {
    const val REQUEST_ID: String = "requestId"
    const val START_NANOS: String = "accessLogStartNanos"
    const val MODE: String = "accessLogMode"
    const val UPSTREAM_STATUS: String = "accessLogUpstreamStatus"
    const val RESPONSE_BYTES: String = "accessLogResponseBytes"

    @JvmStatic
    fun mode(ctx: Context, mode: String) {
        ctx.attribute(MODE, mode)
    }

    @JvmStatic
    fun upstreamStatus(ctx: Context, status: Int) {
        ctx.attribute(UPSTREAM_STATUS, status)
    }

    @JvmStatic
    fun responseBytes(ctx: Context, bytes: Long) {
        ctx.attribute(RESPONSE_BYTES, bytes.coerceAtLeast(0L))
    }

    @JvmStatic
    fun addResponseBytes(ctx: Context, bytes: Long) {
        val current = ctx.attribute<Long>(RESPONSE_BYTES)
        responseBytes(ctx, (current ?: 0L) + bytes.coerceAtLeast(0L))
    }
}
