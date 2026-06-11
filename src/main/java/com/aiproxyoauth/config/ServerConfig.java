package com.aiproxyoauth.config;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.nio.file.Path;

public record ServerConfig(
        String host,
        int port,
        List<String> models,
        String codexVersion,
        String baseUrl,
        String oauthClientId,
        String oauthTokenUrl,
        String oauthFilePath,
        String instructions,
        boolean store,
        Map<String, String> apiKeys,
        String adminKey,
        boolean allowAnyCors,
        List<String> allowedCorsOrigins,
        boolean fullRequestLogging,
        String requestLogDir,
        boolean forwardPromptCacheHeaders,
        String codexInstructionsMode,
        String codexInstructionsCacheDir,
        boolean enableResponsesReplayCache
) {
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 10531;
    public static final String DEFAULT_BASE_URL = "https://chatgpt.com/backend-api/codex";
    public static final String DEFAULT_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    public static final String DEFAULT_ISSUER = "https://auth.openai.com";
    public static final String DEFAULT_INSTRUCTIONS = "";
    public static final String DEFAULT_MODEL = "gpt-5.2";
    public static final String DEFAULT_REQUEST_LOG_DIR = Path.of("logs", "requests")
            .toAbsolutePath()
            .normalize()
            .toString();
    public static final String DEFAULT_CODEX_INSTRUCTIONS_MODE = "configured";
    public static final String DEFAULT_CODEX_INSTRUCTIONS_CACHE_DIR = Path.of("cache", "codex-instructions")
            .toAbsolutePath()
            .normalize()
            .toString();
    public static final String KEY_PREFIX = "sk-proxy-";

    public ServerConfig(
            String host,
            int port,
            List<String> models,
            String codexVersion,
            String baseUrl,
            String oauthClientId,
            String oauthTokenUrl,
            String oauthFilePath,
            String instructions,
            boolean store,
            Map<String, String> apiKeys,
            String adminKey
    ) {
        this(host, port, models, codexVersion, baseUrl, oauthClientId, oauthTokenUrl, oauthFilePath,
                instructions, store, apiKeys, adminKey, false, List.of(), false, null, false, null, null, false);
    }

    public ServerConfig(
            String host,
            int port,
            List<String> models,
            String codexVersion,
            String baseUrl,
            String oauthClientId,
            String oauthTokenUrl,
            String oauthFilePath,
            String instructions,
            boolean store,
            Map<String, String> apiKeys,
            String adminKey,
            boolean allowAnyCors,
            List<String> allowedCorsOrigins
    ) {
        this(host, port, models, codexVersion, baseUrl, oauthClientId, oauthTokenUrl, oauthFilePath,
                instructions, store, apiKeys, adminKey, allowAnyCors, allowedCorsOrigins, false, null, false, null, null, false);
    }

    public ServerConfig(
            String host,
            int port,
            List<String> models,
            String codexVersion,
            String baseUrl,
            String oauthClientId,
            String oauthTokenUrl,
            String oauthFilePath,
            String instructions,
            boolean store,
            Map<String, String> apiKeys,
            String adminKey,
            boolean allowAnyCors,
            List<String> allowedCorsOrigins,
            boolean fullRequestLogging,
            String requestLogDir,
            boolean forwardPromptCacheHeaders
    ) {
        this(host, port, models, codexVersion, baseUrl, oauthClientId, oauthTokenUrl, oauthFilePath,
                instructions, store, apiKeys, adminKey, allowAnyCors, allowedCorsOrigins,
                fullRequestLogging, requestLogDir, forwardPromptCacheHeaders, null, null, false);
    }

    public ServerConfig {
        if (host == null) host = DEFAULT_HOST;
        if (baseUrl == null) baseUrl = DEFAULT_BASE_URL;
        if (oauthClientId == null) oauthClientId = DEFAULT_CLIENT_ID;
        if (instructions == null) instructions = DEFAULT_INSTRUCTIONS;
        requestLogDir = normalizeRequestLogDir(requestLogDir);
        codexInstructionsMode = normalizeCodexInstructionsMode(codexInstructionsMode);
        codexInstructionsCacheDir = normalizeCodexInstructionsCacheDir(codexInstructionsCacheDir);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be in range 1-65535, got: " + port);
        }
        apiKeys = (apiKeys == null) ? Map.of() : Map.copyOf(apiKeys);
        allowedCorsOrigins = normalizeCorsOrigins(allowedCorsOrigins);
        if (!isLocalOnlyHost(host) && apiKeys.isEmpty() && adminKey == null) {
            throw new IllegalArgumentException(
                    "API key enforcement is required when binding to a non-loopback host: " + host
            );
        }
    }

    private static List<String> normalizeCorsOrigins(List<String> origins) {
        if (origins == null) {
            return List.of();
        }
        return origins.stream()
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
    }

    private static String normalizeRequestLogDir(String requestLogDir) {
        if (requestLogDir == null || requestLogDir.isBlank()) {
            return DEFAULT_REQUEST_LOG_DIR;
        }
        return Path.of(requestLogDir).toAbsolutePath().normalize().toString();
    }

    private static String normalizeCodexInstructionsMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return DEFAULT_CODEX_INSTRUCTIONS_MODE;
        }
        String normalized = mode.strip().toLowerCase(Locale.ROOT);
        if (!"configured".equals(normalized) && !"latest-codex".equals(normalized)) {
            throw new IllegalArgumentException("Codex instructions mode must be configured or latest-codex, got: " + mode);
        }
        return normalized;
    }

    private static String normalizeCodexInstructionsCacheDir(String cacheDir) {
        if (cacheDir == null || cacheDir.isBlank()) {
            return DEFAULT_CODEX_INSTRUCTIONS_CACHE_DIR;
        }
        return Path.of(cacheDir).toAbsolutePath().normalize().toString();
    }

    private static boolean isLocalOnlyHost(String host) {
        if (host == null || host.isBlank()) {
            return true;
        }
        String normalized = host.strip().toLowerCase(Locale.ROOT);
        return "localhost".equals(normalized)
                || "::1".equals(normalized)
                || "0:0:0:0:0:0:0:1".equals(normalized)
                || normalized.startsWith("127.");
    }

    public boolean requiresApiKeyEnforcement() {
        return !isLocalOnlyHost(host);
    }
}
