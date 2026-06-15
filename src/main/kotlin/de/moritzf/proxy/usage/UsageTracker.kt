package de.moritzf.proxy.usage
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder
/**
 * Thread-safe accumulator for per-key token usage.
 * Keys map to the human-readable name set at proxy startup.
 * In open mode (no API keys configured) all traffic is recorded under the aggregate key "*".
 */
class UsageTracker {
    data class KeyStats(
        val promptTokens: Long,
        val completionTokens: Long,
    ) {
        val totalTokens: Long
            get() = promptTokens + completionTokens
    }
    private data class Counters(
        val prompt: LongAdder = LongAdder(),
        val completion: LongAdder = LongAdder(),
    )
    private val data = ConcurrentHashMap<String, Counters>()
    /** Records token usage for the given key name. Uses aggregate key "*" in open mode. */
    fun record(keyName: String?, promptTokens: Long, completionTokens: Long) {
        val key = keyName ?: OPEN_MODE_KEY
        val counters = data.computeIfAbsent(key) { Counters() }
        counters.prompt.add(promptTokens)
        counters.completion.add(completionTokens)
    }
    /** Returns a stable snapshot of current totals, sorted by key name. */
    fun snapshot(): Map<String, KeyStats> {
        val result = TreeMap<String, KeyStats>()
        data.forEach { (key, counters) ->
            result[key] = KeyStats(counters.prompt.sum(), counters.completion.sum())
        }
        return result
    }
    companion object {
        /** Sentinel key used in open mode (no API keys configured) to track aggregate proxy usage. */
        const val OPEN_MODE_KEY: String = "*"
    }
}
