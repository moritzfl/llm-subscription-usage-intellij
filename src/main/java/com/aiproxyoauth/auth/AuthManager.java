package com.aiproxyoauth.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.aiproxyoauth.config.ServerConfig;
import com.aiproxyoauth.util.JwtParser;

import java.net.http.HttpClient;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

public class AuthManager {

    private static final long REFRESH_EXPIRY_MARGIN_MS = 5 * 60 * 1000L;

    private final ServerConfig config;
    private final HttpClient httpClient;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile AuthLoader.AuthResult current;

    public AuthManager(ServerConfig config, HttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient;
    }

    public AuthLoader.AuthResult ensureFresh() throws Exception {
        lock.lock();
        try {
            // Re-check after acquiring the lock: another thread may have already refreshed.
            AuthLoader.AuthResult existing = current;
            if (existing != null && !isTokenExpiringSoon(existing.accessToken())) {
                return existing;
            }
            current = AuthLoader.loadAuthTokens(
                    config.oauthFilePath(),
                    config.oauthClientId(),
                    null, // issuer derived from defaults
                    config.oauthTokenUrl(),
                    httpClient
            );
            return current;
        } finally {
            lock.unlock();
        }
    }

    public Map<String, String> getAuthHeaders() throws Exception {
        AuthLoader.AuthResult auth = current;
        if (auth == null || isTokenExpiringSoon(auth.accessToken())) {
            auth = ensureFresh();
        }
        // Map.of() rejects null values with NullPointerException. accountId() is safe here
        // because AuthLoader.loadAuthTokens() throws IOException before returning an AuthResult
        // with a null or empty accountId, so ensureFresh() would have propagated that exception
        // before we reach this point.
        return Map.of(
                "Authorization", "Bearer " + auth.accessToken(),
                "chatgpt-account-id", auth.accountId(),
                "OpenAI-Beta", "responses=experimental"
        );
    }

    private static boolean isTokenExpiringSoon(String accessToken) {
        if (accessToken == null || accessToken.isEmpty()) return true;
        JsonNode claims = JwtParser.parseClaims(accessToken);
        if (claims != null && claims.has("exp") && claims.get("exp").isNumber()) {
            long expiryMs = claims.get("exp").asLong() * 1000;
            return expiryMs <= System.currentTimeMillis() + REFRESH_EXPIRY_MARGIN_MS;
        }
        return false;
    }

    public AuthLoader.AuthResult getCurrent() {
        return current;
    }
}
