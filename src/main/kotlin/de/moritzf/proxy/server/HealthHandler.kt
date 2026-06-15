package de.moritzf.proxy.server

import de.moritzf.proxy.util.ProxyVersion
import io.javalin.http.Context
import io.javalin.http.Handler
import java.util.function.LongSupplier

class HealthHandler(
    private val nanoTime: LongSupplier = LongSupplier { System.nanoTime() },
    private val startedAtNanos: Long = System.nanoTime(),
) : Handler {
    override fun handle(ctx: Context) {
        val response = linkedMapOf<String, Any>(
            "ok" to true,
            "service" to SERVICE_NAME,
            "version" to ProxyVersion.get(),
            "uptime_seconds" to uptimeSeconds(),
        )

        JsonHelper.toJsonResponse(ctx, response)
    }

    private fun uptimeSeconds(): Long {
        val elapsedNanos = (nanoTime.asLong - startedAtNanos).coerceAtLeast(0L)
        return elapsedNanos / 1_000_000_000L
    }

    private companion object {
        private const val SERVICE_NAME = "AIProxyOauth"
    }
}
