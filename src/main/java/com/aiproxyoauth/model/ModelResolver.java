package com.aiproxyoauth.model;

import com.aiproxyoauth.transport.CodexHttpClient;
import com.aiproxyoauth.util.CollectionUtils;
import com.aiproxyoauth.util.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public class ModelResolver {

    private static final long MODELS_CACHE_TTL_MS = 5 * 60 * 1000L;

    private final CodexHttpClient client;
    private final List<String> configuredModels;
    private final String codexVersion;

    private volatile List<String> cachedModels;
    private volatile long modelsCacheExpiresAt;
    private final ReentrantLock modelsLock = new ReentrantLock();

    public ModelResolver(CodexHttpClient client, List<String> configuredModels, String codexVersion) {
        this.client = client;
        this.configuredModels = configuredModels;
        this.codexVersion = codexVersion;
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
        return CodexClientVersionResolver.resolve(codexVersion);
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
