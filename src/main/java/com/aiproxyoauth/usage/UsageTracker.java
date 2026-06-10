package com.aiproxyoauth.usage;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe accumulator for per-key token usage.
 * Keys map to the human-readable name set at proxy startup.
 * In open mode (no API keys configured) all traffic is recorded under the aggregate key "*".
 */
public class UsageTracker {

    /** Sentinel key used in open mode (no API keys configured) to track aggregate proxy usage. */
    public static final String OPEN_MODE_KEY = "*";

    public record KeyStats(long promptTokens, long completionTokens) {
        public long totalTokens() { return promptTokens + completionTokens; }
    }

    private record Counters(LongAdder prompt, LongAdder completion) {
        Counters() { this(new LongAdder(), new LongAdder()); }
    }

    private final ConcurrentHashMap<String, Counters> data = new ConcurrentHashMap<>();

    /** Records token usage for the given key name. Uses aggregate key "*" in open mode (keyName is null). */
    public void record(String keyName, long promptTokens, long completionTokens) {
        if (keyName == null) keyName = OPEN_MODE_KEY;
        Counters c = data.computeIfAbsent(keyName, k -> new Counters());
        c.prompt().add(promptTokens);
        c.completion().add(completionTokens);
    }

    /** Returns a stable snapshot of current totals, sorted by key name. */
    public Map<String, KeyStats> snapshot() {
        Map<String, KeyStats> result = new TreeMap<>();
        data.forEach((k, v) -> result.put(k, new KeyStats(v.prompt().sum(), v.completion().sum())));
        return result;
    }
}
