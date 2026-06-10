package com.aiproxyoauth.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aiproxyoauth.config.ServerConfig;
import com.aiproxyoauth.util.Json;
import com.aiproxyoauth.util.JwtParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;

public final class AuthLoader {

    private static final long REFRESH_EXPIRY_MARGIN_MS = 5 * 60 * 1000L;
    private static final long REFRESH_INTERVAL_MS = 55 * 60 * 1000L;

    private AuthLoader() {}

    public record AuthResult(
            String accessToken,
            String accountId,
            String idToken,
            String refreshToken,
            String sourcePath,
            String lastRefresh
    ) {}

    public static AuthResult loadAuthTokens(
            String authFilePath,
            String clientId,
            String issuer,
            String tokenUrl,
            HttpClient httpClient
    ) throws IOException, InterruptedException {
        if (clientId == null || clientId.isEmpty()) {
            String envClientId = System.getenv("CHATGPT_LOCAL_CLIENT_ID");
            clientId = (envClientId != null && !envClientId.isEmpty())
                    ? envClientId : ServerConfig.DEFAULT_CLIENT_ID;
        }
        if (issuer == null || issuer.isEmpty()) {
            String envIssuer = System.getenv("CHATGPT_LOCAL_ISSUER");
            issuer = (envIssuer != null && !envIssuer.isEmpty())
                    ? envIssuer : ServerConfig.DEFAULT_ISSUER;
        }

        List<String> candidates = AuthFileResolver.resolveCandidates(authFilePath);
        String foundPath = null;
        JsonNode authData = null;

        for (String candidate : candidates) {
            try {
                Path p = Path.of(candidate);
                if (Files.exists(p)) {
                    String content = Files.readString(p);
                    JsonNode parsed = Json.MAPPER.readTree(content);
                    if (parsed != null && parsed.isObject()) {
                        foundPath = candidate;
                        authData = parsed;
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        if (authData == null) {
            authData = Json.MAPPER.createObjectNode();
        }

        JsonNode tokensNode = authData.get("tokens");
        String accessToken = getStringField(tokensNode, "access_token");
        String idToken = getStringField(tokensNode, "id_token");
        String refreshToken = getStringField(tokensNode, "refresh_token");
        String accountId = getStringField(tokensNode, "account_id");
        String lastRefresh = getStringField(authData, "last_refresh");

        if (accountId == null || accountId.isEmpty()) {
            accountId = JwtParser.deriveAccountId(idToken);
        }

        boolean needsRefresh = refreshToken != null && !refreshToken.isEmpty()
                && shouldRefreshAccessToken(accessToken, lastRefresh);

        if (needsRefresh) {
            String resolvedTokenUrl = tokenUrl;
            if (resolvedTokenUrl == null || resolvedTokenUrl.isEmpty()) {
                resolvedTokenUrl = issuer.replaceAll("/$", "") + "/oauth/token";
            }

            RefreshResult refreshed = refreshChatGptTokens(
                    refreshToken, clientId, resolvedTokenUrl, httpClient);

            if (refreshed == null) {
                System.err.println("Warning: OAuth token refresh failed (server returned error). " +
                        "Continuing with existing token.");
            } else {
                accessToken = refreshed.accessToken;
                if (refreshed.idToken != null) idToken = refreshed.idToken;
                if (refreshed.refreshToken != null) refreshToken = refreshed.refreshToken;
                if (refreshed.accountId != null) accountId = refreshed.accountId;
                lastRefresh = Instant.now().toString();

                String writePath = AuthFileResolver.resolveWritePath(
                        foundPath != null ? foundPath : authFilePath);
                writeAuthFile(writePath, authData, idToken, accessToken, refreshToken, accountId, lastRefresh);
            }
        }

        if (accessToken == null || accessToken.isEmpty()) {
            throw new IOException(
                    "ChatGPT access token not found. Run `codex login` to create auth.json.");
        }
        if (accountId == null || accountId.isEmpty()) {
            throw new IOException(
                    "ChatGPT account id not found in auth.json. Run `codex login` to create auth.json.");
        }

        String sourcePath = foundPath != null ? foundPath
                : AuthFileResolver.resolveWritePath(authFilePath);

        return new AuthResult(accessToken, accountId, idToken, refreshToken, sourcePath, lastRefresh);
    }

    private static boolean shouldRefreshAccessToken(String accessToken, String lastRefresh) {
        if (accessToken == null || accessToken.isEmpty()) {
            return true;
        }

        JsonNode claims = JwtParser.parseClaims(accessToken);
        if (claims != null && claims.has("exp") && claims.get("exp").isNumber()) {
            long expiryMs = claims.get("exp").asLong() * 1000;
            if (expiryMs <= System.currentTimeMillis() + REFRESH_EXPIRY_MARGIN_MS) {
                return true;
            }
        }

        if (lastRefresh != null && !lastRefresh.isEmpty()) {
            try {
                Instant refreshedAt = Instant.parse(lastRefresh);
                return refreshedAt.toEpochMilli() <= System.currentTimeMillis() - REFRESH_INTERVAL_MS;
            } catch (Exception ignored) {
            }
        }

        return false;
    }

    private record RefreshResult(String accessToken, String idToken, String refreshToken, String accountId) {}

    private static RefreshResult refreshChatGptTokens(
            String refreshToken, String clientId, String tokenUrl, HttpClient httpClient
    ) throws IOException, InterruptedException {
        ObjectNode body = Json.MAPPER.createObjectNode();
        body.put("grant_type", "refresh_token");
        body.put("refresh_token", refreshToken);
        body.put("client_id", clientId);
        body.put("scope", "openid profile email offline_access");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(tokenUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return null;
        }

        JsonNode payload = Json.MAPPER.readTree(response.body());
        if (payload == null || !payload.isObject()) {
            return null;
        }

        String newAccessToken = getStringField(payload, "access_token");
        if (newAccessToken == null || newAccessToken.isEmpty()) {
            return null;
        }

        String newIdToken = getStringField(payload, "id_token");
        String newRefreshToken = getStringField(payload, "refresh_token");
        if (newRefreshToken == null || newRefreshToken.isEmpty()) {
            newRefreshToken = refreshToken;
        }

        return new RefreshResult(
                newAccessToken,
                newIdToken,
                newRefreshToken,
                JwtParser.deriveAccountId(newIdToken)
        );
    }

    private static void writeAuthFile(String filePath, JsonNode originalData,
                                       String idToken, String accessToken,
                                       String refreshToken, String accountId,
                                       String lastRefresh) {
        try {
            ObjectNode root = originalData.isObject()
                    ? (ObjectNode) originalData.deepCopy()
                    : Json.MAPPER.createObjectNode();

            ObjectNode tokens = Json.MAPPER.createObjectNode();
            if (idToken != null) tokens.put("id_token", idToken);
            if (accessToken != null) tokens.put("access_token", accessToken);
            if (refreshToken != null) tokens.put("refresh_token", refreshToken);
            if (accountId != null) tokens.put("account_id", accountId);
            root.set("tokens", tokens);
            root.put("last_refresh", lastRefresh);

            Path path = Path.of(filePath);
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);

            // Write to a sibling temp file first, then rename atomically to avoid
            // leaving a truncated auth.json if the JVM crashes mid-write.
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");

            // Set strict permissions BEFORE writing any content
            setStrictFilePermissions(tmp);

            Files.writeString(tmp, Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            System.err.println("Warning: failed to write auth file to " + filePath + ": " + e);
        }
    }

    private static void setStrictFilePermissions(Path path) {
        try {
            if (!Files.exists(path)) {
                Files.createFile(path);
            }

            if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                // POSIX (Linux, macOS): chmod 600
                java.nio.file.attribute.PosixFilePermission[] perms = {
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
                };
                Files.setPosixFilePermissions(path, java.util.Set.of(perms));
            } else if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("acl")) {
                // Windows: ACLs
                java.nio.file.attribute.AclFileAttributeView view = Files.getFileAttributeView(path, java.nio.file.attribute.AclFileAttributeView.class);
                java.nio.file.attribute.UserPrincipal owner = Files.getOwner(path);

                java.nio.file.attribute.AclEntry entry = java.nio.file.attribute.AclEntry.newBuilder()
                        .setType(java.nio.file.attribute.AclEntryType.ALLOW)
                        .setPrincipal(owner)
                        .setPermissions(
                                java.nio.file.attribute.AclEntryPermission.READ_DATA,
                                java.nio.file.attribute.AclEntryPermission.WRITE_DATA,
                                java.nio.file.attribute.AclEntryPermission.APPEND_DATA,
                                java.nio.file.attribute.AclEntryPermission.READ_NAMED_ATTRS,
                                java.nio.file.attribute.AclEntryPermission.WRITE_NAMED_ATTRS,
                                java.nio.file.attribute.AclEntryPermission.READ_ATTRIBUTES,
                                java.nio.file.attribute.AclEntryPermission.WRITE_ATTRIBUTES,
                                java.nio.file.attribute.AclEntryPermission.READ_ACL,
                                java.nio.file.attribute.AclEntryPermission.WRITE_ACL,
                                java.nio.file.attribute.AclEntryPermission.WRITE_OWNER,
                                java.nio.file.attribute.AclEntryPermission.SYNCHRONIZE,
                                java.nio.file.attribute.AclEntryPermission.DELETE
                        )
                        .build();

                // Set the owner-only ACL
                view.setAcl(java.util.List.of(entry));
            }
        } catch (Exception e) {
            System.err.println("Warning: could not set strict file permissions on " + path + ": " + e.getMessage());
        }
    }

    private static String getStringField(JsonNode node, String field) {
        if (node == null || !node.has(field)) return null;
        JsonNode value = node.get(field);
        return value.isTextual() && !value.asText().isEmpty() ? value.asText() : null;
    }
}
