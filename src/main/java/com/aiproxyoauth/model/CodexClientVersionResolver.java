package com.aiproxyoauth.model;

import com.aiproxyoauth.util.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class CodexClientVersionResolver {
    public static final String FALLBACK_CODEX_CLIENT_VERSION = "0.121.0";

    private static final Pattern VERSION_PATTERN = Pattern.compile("\\b\\d+\\.\\d+\\.\\d+\\b");
    private static final String REGISTRY_URL = "https://registry.npmjs.org/@openai/codex/latest";
    private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<>();

    private CodexClientVersionResolver() {}

    public static String resolve(String configuredVersion) {
        if (configuredVersion != null && !configuredVersion.trim().isEmpty()) {
            return configuredVersion.trim();
        }
        return CACHE.computeIfAbsent("default", ignored -> {
            String local = resolveLocalCodexVersion();
            if (local != null) {
                return local;
            }
            String remote = resolveRemoteCodexVersion();
            if (remote != null) {
                return remote;
            }
            System.err.println("Could not determine the Codex API version automatically. " +
                    "Falling back to " + FALLBACK_CODEX_CLIENT_VERSION +
                    ". Pass a version explicitly with --codex-version if you need to override it.");
            return FALLBACK_CODEX_CLIENT_VERSION;
        });
    }

    static String resolveLocalCodexVersion() {
        for (List<String> command : localCodexVersionCommands(isWindows())) {
            String output = runVersionCommand(command);
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

    static String runVersionCommand(List<String> command) {
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

    static String resolveRemoteCodexVersion() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(REGISTRY_URL))
                    .header("Accept", "application/json")
                    .timeout(java.time.Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }
            JsonNode parsed = Json.MAPPER.readTree(response.body());
            JsonNode version = parsed.get("version");
            return version != null && version.isTextual() ? normalizeVersion(version.asText()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    static String normalizeVersion(String raw) {
        if (raw == null) {
            return null;
        }
        java.util.regex.Matcher matcher = VERSION_PATTERN.matcher(raw);
        return matcher.find() ? matcher.group() : null;
    }
}
