package com.aiproxyoauth.util;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Base64;

public final class JwtParser {

    private JwtParser() {}

    public static JsonNode parseClaims(String token) {
        if (token == null || !token.contains(".")) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3 || parts[1].isEmpty()) {
            return null;
        }
        try {
            String padded = parts[1];
            int remainder = padded.length() % 4;
            if (remainder > 0) {
                padded += "=".repeat(4 - remainder);
            }
            byte[] decoded = Base64.getUrlDecoder().decode(padded);
            String payload = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
            JsonNode node = Json.MAPPER.readTree(payload);
            return node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static String deriveAccountId(String idToken) {
        JsonNode claims = parseClaims(idToken);
        if (claims == null) {
            return null;
        }
        JsonNode authClaim = claims.get("https://api.openai.com/auth");
        if (authClaim != null && authClaim.isObject()) {
            JsonNode accountId = authClaim.get("chatgpt_account_id");
            if (accountId != null && accountId.isTextual() && !accountId.asText().isEmpty()) {
                return accountId.asText();
            }
        }
        return null;
    }
}
