package de.moritzf.quota;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for quota response parsing and input validation in {@link OpenAiCodexQuotaClient}.
 */
class OpenAiCodexQuotaClientTest {
    private final OpenAiCodexQuotaClient client = new OpenAiCodexQuotaClient();

    @Test
    void parseQuotaMapsTopLevelAndWindowFields() throws Exception {
        Instant fetchedAt = Instant.parse("2026-01-02T03:04:05Z");
        @Language("JSON")
        String json = """
                {
                  "user_id": "user-1",
                  "account_id": "account-1",
                  "email": "user@example.com",
                  "plan_type": "pro",
                  "rate_limit": {
                    "allowed": true,
                    "limit_reached": false,
                    "primary_window": {
                      "used_percent": 12.3,
                      "limit_window_seconds": 18000,
                      "reset_at": 1735689600
                    },
                    "secondary_window": {
                      "used_percent": 45.6,
                      "limit_window_seconds": 604800,
                      "reset_at": 1736294400
                    }
                  }
                }
                """;

        OpenAiCodexQuota quota = client.parseQuota(json, fetchedAt);

        assertEquals("pro", quota.getPlanType());
        assertEquals("account-1", quota.getAccountId());
        assertEquals("user@example.com", quota.getEmail());
        assertEquals(Boolean.TRUE, quota.getAllowed());
        assertEquals(Boolean.FALSE, quota.getLimitReached());
        assertEquals(fetchedAt, quota.getFetchedAt());
        assertEquals(json, quota.getRawJson());

        assertNotNull(quota.getPrimary());
        assertEquals(12.3, quota.getPrimary().getUsedPercent(), 0.0001);
        assertEquals(Integer.valueOf(300), quota.getPrimary().getWindowMinutes());
        assertEquals(Instant.ofEpochSecond(1735689600), quota.getPrimary().getResetsAt());

        assertNotNull(quota.getSecondary());
        assertEquals(45.6, quota.getSecondary().getUsedPercent(), 0.0001);
        assertEquals(Integer.valueOf(10080), quota.getSecondary().getWindowMinutes());
        assertEquals(Instant.ofEpochSecond(1736294400), quota.getSecondary().getResetsAt());
    }

    @Test
    void parseQuotaClampsPercentValuesAndAllowsMissingOptionalWindowFields() throws Exception {
        @Language("JSON")
        String json = """
                {
                  "rate_limit": {
                    "primary_window": { "used_percent": -5.0 },
                    "secondary_window": { "used_percent": 101.0 }
                  }
                }
                """;

        OpenAiCodexQuota quota = client.parseQuota(json, Instant.EPOCH);

        assertNotNull(quota.getPrimary());
        assertEquals(0.0, quota.getPrimary().getUsedPercent(), 0.0);
        assertNull(quota.getPrimary().getWindowMinutes());
        assertNull(quota.getPrimary().getResetsAt());

        assertNotNull(quota.getSecondary());
        assertEquals(100.0, quota.getSecondary().getUsedPercent(), 0.0);
        assertNull(quota.getSecondary().getWindowMinutes());
        assertNull(quota.getSecondary().getResetsAt());
    }

    @Test
    void parseQuotaThrowsWhenNoUsableWindowsArePresent() {
        @Language("JSON")
        String json = """
                {
                  "rate_limit": {
                    "allowed": true,
                    "limit_reached": false
                  }
                }
                """;

        OpenAiCodexQuotaException exception = assertThrows(
                OpenAiCodexQuotaException.class,
                () -> client.parseQuota(json, Instant.EPOCH)
        );

        assertEquals(200, exception.getStatusCode());
        assertTrue(exception.getMessage().contains("did not include rate_limit windows"));
    }
}
