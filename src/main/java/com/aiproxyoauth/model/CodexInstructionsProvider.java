package com.aiproxyoauth.model;

import com.aiproxyoauth.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public final class CodexInstructionsProvider {

    public enum Mode {
        CONFIGURED,
        LATEST_CODEX
    }

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(15);
    private static final String DEFAULT_SOURCE_BASE = "https://chatgpt.com/backend-api/codex/instructions/";
    private static final String[] REASONING_SUFFIXES = {"-minimal", "-medium", "-xhigh", "-none", "-high", "-low"};

    private final Mode mode;
    private final String configuredInstructions;
    private final Path cacheDir;
    private final Duration ttl;
    private final Clock clock;
    private final InstructionFetcher fetcher;
    private final Map<String, CacheEntry> memoryCache = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    public CodexInstructionsProvider(String configuredInstructions) {
        this(Mode.CONFIGURED, configuredInstructions, defaultCacheDir(), DEFAULT_TTL, Clock.systemUTC(), defaultHttpFetcher());
    }

    public CodexInstructionsProvider(Mode mode, String configuredInstructions, Path cacheDir, Duration ttl,
                                     HttpClient httpClient) {
        this(mode, configuredInstructions, cacheDir, ttl, Clock.systemUTC(), httpFetcher(httpClient));
    }

    public CodexInstructionsProvider(Mode mode, String configuredInstructions, Path cacheDir, Duration ttl,
                                     Clock clock, InstructionFetcher fetcher) {
        this.mode = mode == null ? Mode.CONFIGURED : mode;
        this.configuredInstructions = configuredInstructions == null ? "" : configuredInstructions;
        this.cacheDir = cacheDir == null ? defaultCacheDir() : cacheDir;
        this.ttl = ttl == null ? DEFAULT_TTL : ttl;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
    }

    public String instructionsForModel(String model) {
        if (mode == Mode.CONFIGURED) {
            return configuredInstructions;
        }

        String modelFamily = modelFamily(model);
        Instant now = clock.instant();
        CacheEntry cached = loadCache(modelFamily);
        if (cached != null && cached.isFresh(now, ttl)) {
            return cached.instructions();
        }

        lock.lock();
        try {
            cached = loadCache(modelFamily);
            now = clock.instant();
            if (cached != null && cached.isFresh(now, ttl)) {
                return cached.instructions();
            }

            FetchResponse response = fetchLatest(modelFamily, cached);
            if (response != null && response.statusCode() == 304 && cached != null) {
                CacheEntry refreshed = cached.withFetchedAt(now);
                saveCache(refreshed);
                return refreshed.instructions();
            }
            if (response != null && response.statusCode() >= 200 && response.statusCode() < 300) {
                String instructions = extractInstructions(response.body());
                CacheEntry updated = new CacheEntry(
                        modelFamily,
                        sourceUri(modelFamily).toString(),
                        headerValue(response.headers(), "ETag"),
                        now,
                        instructions);
                saveCache(updated);
                return updated.instructions();
            }
        } catch (Exception ignored) {
            // Latest-Codex mode is optional; stale cache or configured instructions are safer than failing requests.
        } finally {
            lock.unlock();
        }

        CacheEntry fallback = loadCache(modelFamily);
        return fallback != null ? fallback.instructions() : configuredInstructions;
    }

    public static String modelFamily(String model) {
        if (model == null || model.isBlank()) {
            return "default";
        }
        String normalized = model.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        for (String suffix : REASONING_SUFFIXES) {
            if (lower.endsWith(suffix) && normalized.length() > suffix.length()) {
                return normalized.substring(0, normalized.length() - suffix.length());
            }
        }
        return normalized;
    }

    private FetchResponse fetchLatest(String modelFamily, CacheEntry cached) throws Exception {
        Map<String, String> headers = new HashMap<>();
        if (cached != null && cached.etag() != null && !cached.etag().isBlank()) {
            headers.put("If-None-Match", cached.etag());
        }
        return fetcher.fetch(new FetchRequest(modelFamily, sourceUri(modelFamily), Map.copyOf(headers)));
    }

    private CacheEntry loadCache(String modelFamily) {
        CacheEntry cached = memoryCache.get(modelFamily);
        if (cached != null) {
            return cached;
        }

        Path cacheFile = cacheFile(modelFamily);
        if (!Files.exists(cacheFile)) {
            return null;
        }
        try {
            JsonNode node = Json.MAPPER.readTree(Files.readString(cacheFile));
            CacheEntry loaded = new CacheEntry(
                    node.path("modelFamily").asText(modelFamily),
                    node.path("sourceUrl").asText(sourceUri(modelFamily).toString()),
                    textOrNull(node.get("etag")),
                    parseInstant(node),
                    node.path("instructions").asText(""));
            memoryCache.put(modelFamily, loaded);
            return loaded;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void saveCache(CacheEntry entry) throws IOException {
        Files.createDirectories(cacheDir);
        ObjectNode node = Json.MAPPER.createObjectNode();
        node.put("modelFamily", entry.modelFamily());
        node.put("sourceUrl", entry.sourceUrl());
        if (entry.etag() != null) {
            node.put("etag", entry.etag());
        }
        node.put("fetchedAt", entry.fetchedAt().toString());
        node.put("timestamp", entry.fetchedAt().toString());
        node.put("fetchedAtEpochMillis", entry.fetchedAt().toEpochMilli());
        node.put("instructions", entry.instructions());
        Files.writeString(cacheFile(entry.modelFamily()), Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node));
        memoryCache.put(entry.modelFamily(), entry);
    }

    private Path cacheFile(String modelFamily) {
        return cacheDir.resolve(safeFileName(modelFamily) + ".json");
    }

    private static String safeFileName(String modelFamily) {
        return modelFamily.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static URI sourceUri(String modelFamily) {
        return URI.create(DEFAULT_SOURCE_BASE + safeFileName(modelFamily));
    }

    private static String extractInstructions(String body) {
        if (body == null) {
            return "";
        }
        try {
            JsonNode parsed = Json.MAPPER.readTree(body);
            JsonNode instructions = parsed.get("instructions");
            if (instructions != null && instructions.isTextual()) {
                return instructions.asText();
            }
        } catch (Exception ignored) {
        }
        return body;
    }

    private static String headerValue(Map<String, String> headers, String name) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String textOrNull(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static Instant parseInstant(JsonNode node) {
        String fetchedAt = node.path("fetchedAt").asText(null);
        if (fetchedAt == null || fetchedAt.isBlank()) {
            fetchedAt = node.path("timestamp").asText(null);
        }
        if (fetchedAt != null && !fetchedAt.isBlank()) {
            return Instant.parse(fetchedAt);
        }
        long epochMillis = node.path("fetchedAtEpochMillis").asLong(0L);
        return epochMillis > 0 ? Instant.ofEpochMilli(epochMillis) : Instant.EPOCH;
    }

    private static Path defaultCacheDir() {
        return Path.of("cache", "codex-instructions");
    }

    private static InstructionFetcher defaultHttpFetcher() {
        return httpFetcher(HttpClient.newHttpClient());
    }

    private static InstructionFetcher httpFetcher(HttpClient httpClient) {
        HttpClient client = Objects.requireNonNull(httpClient, "httpClient");
        return request -> {
            HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                    .header("Accept", "application/json")
                    .GET();
            request.headers().forEach(builder::header);
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            Map<String, String> headers = new HashMap<>();
            response.headers().map().forEach((key, values) -> {
                if (!values.isEmpty()) {
                    headers.put(key, values.getFirst());
                }
            });
            return new FetchResponse(response.statusCode(), response.body(), headers);
        };
    }

    @FunctionalInterface
    public interface InstructionFetcher {
        FetchResponse fetch(FetchRequest request) throws Exception;
    }

    public record FetchRequest(String modelFamily, URI uri, Map<String, String> headers) {}

    public record FetchResponse(int statusCode, String body, Map<String, String> headers) {
        public FetchResponse {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    private record CacheEntry(String modelFamily, String sourceUrl, String etag, Instant fetchedAt,
                              String instructions) {
        private boolean isFresh(Instant now, Duration ttl) {
            return !fetchedAt.plus(ttl).isBefore(now);
        }

        private CacheEntry withFetchedAt(Instant fetchedAt) {
            return new CacheEntry(modelFamily, sourceUrl, etag, fetchedAt, instructions);
        }
    }
}
