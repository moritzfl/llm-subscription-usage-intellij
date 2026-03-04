package de.moritzf.quota.idea;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for extracting account metadata from OAuth/JWT tokens.
 */
class QuotaTokenUtilTest {
    @Test
    void extractChatGptAccountIdReturnsTrimmedAccountIdWhenPresent() {
        @Language("JSON")
        String payload = """
                {
                  "https://api.openai.com/auth": {
                    "chatgpt_account_id": "  account-123  "
                  }
                }
                """;

        String token = buildToken(payload);

        assertEquals("account-123", QuotaTokenUtil.extractChatGptAccountId(token));
    }

    @Test
    void extractChatGptAccountIdReturnsNullWhenClaimIsMissing() {
        @Language("JSON")
        String payload = """
                {
                  "sub": "user-1"
                }
                """;

        String token = buildToken(payload);

        assertNull(QuotaTokenUtil.extractChatGptAccountId(token));
    }

    @Test
    void extractChatGptAccountIdReturnsNullForMalformedToken() {
        assertNull(QuotaTokenUtil.extractChatGptAccountId("not-a-jwt"));
    }

    @Test
    void extractChatGptAccountIdReturnsNullForBlankToken() {
        assertNull(QuotaTokenUtil.extractChatGptAccountId("   "));
    }

    private static String buildToken(@Language("JSON") String payloadJson) {
        @Language("JSON")
        String headerJson = """
                {"alg":"none","typ":"JWT"}
                """;
        return base64Url(headerJson) + "." + base64Url(payloadJson) + ".signature";
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
