package de.moritzf.quota.idea;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.moritzf.quota.dto.OpenAiAuthorizationDto;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Utilities for extracting OpenAI account metadata from JWT tokens.
 */
public final class QuotaTokenUtil {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private QuotaTokenUtil() {
    }

    public static @Nullable String extractChatGptAccountId(@Nullable String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            String json = new String(decoded, StandardCharsets.UTF_8);
            OpenAiAuthorizationDto openAiAuth = null;
            JsonNode payload = MAPPER.readTree(json);
            JsonNode openAiAuthNode = payload.get("https://api.openai.com/auth");
            if (openAiAuthNode != null && !openAiAuthNode.isNull()) {
                openAiAuth = MAPPER.treeToValue(openAiAuthNode, OpenAiAuthorizationDto.class);
            }
            String accountId = openAiAuth == null ? null : openAiAuth.chatgptAccountId();
            if (accountId != null && !accountId.isBlank()) {
                return accountId.trim();
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }
}
