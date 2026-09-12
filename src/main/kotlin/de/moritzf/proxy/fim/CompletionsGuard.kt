package de.moritzf.proxy.fim

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job

class CompletionsGuard(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val inFlight = ConcurrentHashMap<String, InFlight>()
    private val lastStartMillis = ConcurrentHashMap<String, Long>()
    private val requestTimes = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val circuitOpenUntil = ConcurrentHashMap<String, Long>()
    private val quotaErrors = ConcurrentHashMap<String, Int>()

    fun tryStart(
        key: String,
        config: CompletionsConfig,
        job: Job?,
        fingerprint: String = "",
        coalesce: Boolean = true,
    ): GuardDecision {
        if (!config.enabled) return GuardDecision.Skip("disabled")
        if (config.modelLocalId.isBlank()) return GuardDecision.Skip("no-model")
        val now = clock()
        val openUntil = circuitOpenUntil[key]
        if (openUntil != null && now < openUntil) {
            return GuardDecision.Skip("circuit-open")
        }
        if (coalesce) {
            val existing = inFlight[key]
            if (existing != null && existing.job.isActive && existing.fingerprint == fingerprint) {
                return GuardDecision.Join(existing.result)
            }
        }
        val last = lastStartMillis[key]
        if (last != null && now - last < config.minIntervalMillis) {
            return GuardDecision.Skip("min-interval")
        }
        val window = requestTimes.getOrPut(key) { ArrayDeque() }
        synchronized(window) {
            while (window.isNotEmpty() && now - window.peekFirst() >= 60_000L) {
                window.removeFirst()
            }
            if (window.size >= config.maxRequestsPerMinute) {
                return GuardDecision.Skip("rpm")
            }
            window.addLast(now)
        }
        if (job != null) {
            val next = InFlight(fingerprint = fingerprint, job = job, result = CompletableDeferred())
            val previous = inFlight.put(key, next)
            if (previous != null && previous.job.isActive && previous.job !== job) {
                previous.job.cancel()
            }
            previous?.result?.complete("")
        }
        lastStartMillis[key] = now
        return GuardDecision.Allow
    }

    fun publish(key: String, job: Job?, text: String) {
        val current = inFlight[key] ?: return
        if (job != null && current.job !== job) return
        current.result.complete(text)
    }

    fun finish(key: String, job: Job?) {
        inFlight.computeIfPresent(key) { _, current ->
            if (job == null || current.job === job) {
                current.result.complete("")
                null
            } else {
                current
            }
        }
    }

    fun noteQuotaError(key: String) {
        val count = quotaErrors.merge(key, 1) { previous, _ -> previous + 1 } ?: 1
        if (count >= CIRCUIT_ERROR_THRESHOLD) {
            circuitOpenUntil[key] = clock() + CIRCUIT_OPEN_MILLIS
            quotaErrors[key] = 0
        }
    }

    fun noteSuccess(key: String) {
        quotaErrors.remove(key)
    }

    private data class InFlight(
        val fingerprint: String,
        val job: Job,
        val result: CompletableDeferred<String>,
    )

    companion object {
        const val CIRCUIT_ERROR_THRESHOLD = 3
        const val CIRCUIT_OPEN_MILLIS = 5 * 60_000L

        fun clampMaxTokens(requested: Int?, config: CompletionsConfig): Int {
            val cap = CompletionsConfig.clampMaxOutputTokens(config.maxOutputTokens)
            if (requested == null || requested <= 0) return cap
            return requested.coerceAtMost(cap).coerceAtLeast(CompletionsConfig.MIN_OUTPUT_TOKENS)
        }

        fun budget(context: FimContext, maxChars: Int): FimContext {
            if (maxChars <= 0) return context.copy(extraFiles = emptyList(), prefix = "", suffix = "")
            var remaining = maxChars
            val extra = ArrayList<FimFileSlice>()
            for (slice in context.extraFiles) {
                val size = slice.path.length + slice.content.length
                if (size > remaining) continue
                extra += slice
                remaining -= size
            }
            val suffixKeep = minOf(context.suffix.length, remaining / 3)
            val suffix = context.suffix.take(suffixKeep)
            remaining -= suffix.length
            val prefix = if (context.prefix.length <= remaining) {
                context.prefix
            } else {
                context.prefix.takeLast(remaining)
            }
            return context.copy(prefix = prefix, suffix = suffix, extraFiles = extra)
        }
    }
}

sealed class GuardDecision {
    data object Allow : GuardDecision()
    data class Skip(val reason: String) : GuardDecision()
    data class Join(val result: Deferred<String>) : GuardDecision()
}
