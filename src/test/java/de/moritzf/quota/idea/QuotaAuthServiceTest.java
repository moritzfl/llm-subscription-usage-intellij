package de.moritzf.quota.idea;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for OAuth callback URI and query parsing helpers.
 */
class QuotaAuthServiceTest {
    @Test
    void parseUriAcceptsFullCallbackUrl() {
        URI uri = QuotaAuthService.parseUri("http://127.0.0.1:1455/auth/callback?code=abc&state=xyz");
        assertEquals("/auth/callback", uri.getPath());
        assertEquals("code=abc&state=xyz", uri.getRawQuery());
    }

    @Test
    void parseUriAcceptsRelativeCallbackUrl() {
        URI uri = QuotaAuthService.parseUri("/auth/callback?code=abc&state=xyz");
        assertEquals("/auth/callback", uri.getPath());
        assertEquals("code=abc&state=xyz", uri.getRawQuery());
    }

    @Test
    void parseUriAcceptsQueryOnly() {
        URI uri = QuotaAuthService.parseUri("code=abc&state=xyz");
        assertEquals("/auth/callback", uri.getPath());
        assertEquals("code=abc&state=xyz", uri.getRawQuery());
    }

    @Test
    void parseQueryDecodesValues() {
        Map<String, String> params = QuotaAuthService.parseQuery("code=abc%20123&state=xy%2Bz");
        assertEquals("abc 123", params.get("code"));
        assertEquals("xy+z", params.get("state"));
    }

    @Test
    void parseQueryEmptyReturnsEmptyMap() {
        Map<String, String> params = QuotaAuthService.parseQuery("");
        assertTrue(params.isEmpty());
    }
}
