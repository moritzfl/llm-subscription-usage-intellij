package com.aiproxyoauth.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.aiproxyoauth.transport.CodexHttpClient;
import com.aiproxyoauth.util.CollectionUtils;
import com.aiproxyoauth.util.Json;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

public class ModelResolver {

    private static final long MODELS_CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final Pattern VERSION_PATTERN = Pattern.compile("\\b\\d+\\.\\d+\\.\\d+\\b");
    private static final long CODEX_VERSION_CACHE_TTL_MS = 60 * 60 * 1000L;
    private static final String REGISTRY_URL = "https://registry.npmjs.org/@openai/codex/latest";
    private static final String FALLBACK_CODEX_CLIENT_VERSION = "0.121.0";

    private final CodexHttpClient client;
    private final List<String> configuredModels;
    private final String codexVersion;
    private final VersionCommandRunner versionCommandRunner;

    private volatile List<String> cachedModels;
    private volatile long modelsCacheExpiresAt;
    private volatile String cachedCodexVersion;
    private volatile long codexVersionCacheExpiresAt;
    // Two separate locks so that model-list resolution never blocks codex-version resolution
    // and vice versa — previously a single lock caused up to 15-second stalls on cold start
    // because resolveModels() called resolveCodexClientVersion() while holding the same lock.
    private final ReentrantLock modelsLock = new ReentrantLock();
    private final ReentrantLock codexVersionLock = new ReentrantLock();

    public ModelResolver(CodexHttpClient client, List<String> configuredModels, String codexVersion) {
        this(client, configuredModels, codexVersion, ModelResolver::runVersionCommand);
    }

    ModelResolver(CodexHttpClient client, List<String> configuredModels, String codexVersion,
                  VersionCommandRunner versionCommandRunner) {
        this.client = client;
        this.configuredModels = configuredModels;
        this.codexVersion = codexVersion;
        this.versionCommandRunner = versionCommandRunner;
    }

    public List<String> resolveModels() throws Exception {
        if (configuredModels != null && !configuredModels.isEmpty()) {
            return CollectionUtils.uniqueStrings(configuredModels);
        }

        long now = System.currentTimeMillis();
        List<String> cached = cachedModels;
        if (cached != null && now < modelsCacheExpiresAt) {
            return new ArrayList<>(cached);
        }

        modelsLock.lock();
        try {
            // Double-check after acquiring lock
            cached = cachedModels;
            if (cached != null && System.currentTimeMillis() < modelsCacheExpiresAt) {
                return new ArrayList<>(cached);
            }

            List<String> models = fetchAvailableModels();
            cachedModels = models;
            modelsCacheExpiresAt = System.currentTimeMillis() + MODELS_CACHE_TTL_MS;
            return new ArrayList<>(models);
        } finally {
            modelsLock.unlock();
        }
    }

    public String resolveCodexClientVersion() {
        if (codexVersion != null && !codexVersion.trim().isEmpty()) {
            return codexVersion.trim();
        }

        long now = System.currentTimeMillis();
        if (cachedCodexVersion != null && now < codexVersionCacheExpiresAt) {
            return cachedCodexVersion;
        }

        codexVersionLock.lock();
        try {
            // Double-check after acquiring lock
            if (cachedCodexVersion != null && System.currentTimeMillis() < codexVersionCacheExpiresAt) {
                return cachedCodexVersion;
            }

            // Try local codex --version
            String local = resolveLocalCodexVersion();
            if (local != null) {
                cachedCodexVersion = local;
                codexVersionCacheExpiresAt = System.currentTimeMillis() + CODEX_VERSION_CACHE_TTL_MS;
                return local;
            }

            // Try npm registry
            String remote = resolveRemoteCodexVersion();
            if (remote != null) {
                cachedCodexVersion = remote;
                codexVersionCacheExpiresAt = System.currentTimeMillis() + CODEX_VERSION_CACHE_TTL_MS;
                return remote;
            }

            cachedCodexVersion = FALLBACK_CODEX_CLIENT_VERSION;
            codexVersionCacheExpiresAt = System.currentTimeMillis() + CODEX_VERSION_CACHE_TTL_MS;
            System.err.println("Could not determine the Codex API version automatically. " +
                    "Falling back to " + FALLBACK_CODEX_CLIENT_VERSION +
                    ". Pass a version explicitly with --codex-version if you need to override it.");
            return FALLBACK_CODEX_CLIENT_VERSION;
        } finally {
            codexVersionLock.unlock();
        }
    }

    private List<String> fetchAvailableModels() throws Exception {
        String clientVersion = resolveCodexClientVersion();
        String path = "/models?client_version=" + URLEncoder.encode(clientVersion, StandardCharsets.UTF_8);

        HttpResponse<String> response = client.requestString(path, "GET", null, null);

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String msg = extractUpstreamError(response.body());
            throw new RuntimeException(msg != null ? msg : "Failed to load models from Codex.");
        }

        JsonNode parsed = Json.MAPPER.readTree(response.body());
        JsonNode modelsNode = parsed.get("models");
        if (modelsNode == null || !modelsNode.isArray()) {
            throw new RuntimeException("Codex returned a malformed models response.");
        }

        List<String> models = new ArrayList<>();
        for (JsonNode model : modelsNode) {
            JsonNode slug = model.get("slug");
            if (slug != null && slug.isTextual() && !slug.asText().isEmpty()) {
                models.add(slug.asText());
            }
        }

        models = CollectionUtils.uniqueStrings(models);
        if (models.isEmpty()) {
            throw new RuntimeException("Codex returned an empty models list.");
        }

        return models;
    }

    private String resolveLocalCodexVersion() {
        for (List<String> command : localCodexVersionCommands(isWindows())) {
            String output = versionCommandRunner.run(command);
            String version = normalizeVersion(output);
            if (version != null) {
                return version;
            }
        }
        return null;
    }

    static List<List<String>> localCodexVersionCommands(boolean windows) {
        if (windows) {
            return List.of(
                    List.of("cmd.exe", "/c", "codex.cmd", "--version"),
                    List.of("codex.exe", "--version"),
                    List.of("codex", "--version")
            );
        }
        return List.of(List.of("codex", "--version"));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static String runVersionCommand(List<String> command) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            process = pb.start();
            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!output.isEmpty()) {
                        output.append('\n');
                    }
                    output.append(line);
                }
            }
            return output.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    @FunctionalInterface
    interface VersionCommandRunner {
        String run(List<String> command);
    }

    private String resolveRemoteCodexVersion() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(REGISTRY_URL))
                    .header("Accept", "application/json")
                    .timeout(java.time.Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = client.getHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                JsonNode parsed = Json.MAPPER.readTree(response.body());
                JsonNode version = parsed.get("version");
                if (version != null && version.isTextual()) {
                    return normalizeVersion(version.asText());
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String normalizeVersion(String value) {
        if (value == null) return null;
        java.util.regex.Matcher m = VERSION_PATTERN.matcher(value.trim());
        return m.find() ? m.group() : null;
    }

    private static String extractUpstreamError(String bodyText) {
        if (bodyText == null || bodyText.isEmpty()) return null;
        try {
            JsonNode parsed = Json.MAPPER.readTree(bodyText);
            JsonNode detail = parsed.get("detail");
            if (detail != null && detail.isTextual() && !detail.asText().isEmpty()) {
                return detail.asText();
            }
            JsonNode error = parsed.get("error");
            if (error != null && error.isObject()) {
                JsonNode msg = error.get("message");
                if (msg != null && msg.isTextual()) {
                    return msg.asText();
                }
            }
        } catch (Exception ignored) {
        }
        return bodyText;
    }
}
