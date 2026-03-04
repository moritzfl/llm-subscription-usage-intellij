package de.moritzf.quota.idea.auth;

import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * URL encoding and parsing helpers shared across OAuth components.
 */
public final class OAuthUrlCodec {
    private OAuthUrlCodec() {
    }

    public static @NotNull String formEncode(@NotNull Map<String, String> params) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!builder.isEmpty()) {
                builder.append("&");
            }
            builder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            builder.append("=");
            builder.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    public static @NotNull Map<String, String> parseQuery(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query == null || query.isBlank()) {
            return params;
        }
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
            params.put(key, value);
        }
        return params;
    }

    public static @NotNull URI parseCallbackUri(String value, String redirectUri) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            return URI.create("");
        }
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return URI.create(trimmed);
        }
        if (trimmed.startsWith("/auth/callback")) {
            return URI.create(redirectUri + trimmed.substring("/auth/callback".length()));
        }
        return URI.create(redirectUri + "?" + trimmed);
    }
}
