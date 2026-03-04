package de.moritzf.quota.idea.auth;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for OAuth URL encoding and callback/query parsing helpers.
 */
class OAuthUrlCodecTest {
    @Test
    void formEncodeEncodesReservedCharacters() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("state", "a b+c");
        params.put("redirect_uri", "http://localhost:1455/auth/callback?x=1&y=2");

        String encoded = OAuthUrlCodec.formEncode(params);

        assertEquals(
                "state=a+b%2Bc&redirect_uri=http%3A%2F%2Flocalhost%3A1455%2Fauth%2Fcallback%3Fx%3D1%26y%3D2",
                encoded
        );
    }

    @Test
    void parseQueryDecodesValuesAndSkipsInvalidPairs() {
        Map<String, String> params = OAuthUrlCodec.parseQuery("code=abc%20123&state=xy%2Bz&invalid&=skip&k=v");

        assertEquals("abc 123", params.get("code"));
        assertEquals("xy+z", params.get("state"));
        assertEquals("v", params.get("k"));
        assertEquals(3, params.size());
    }

    @Test
    void parseCallbackUriAcceptsAbsoluteAndRelativeForms() {
        String redirectUri = "http://localhost:1455/auth/callback";

        URI absolute = OAuthUrlCodec.parseCallbackUri("https://example.com/auth/callback?code=abc", redirectUri);
        assertEquals("https://example.com/auth/callback?code=abc", absolute.toString());

        URI relative = OAuthUrlCodec.parseCallbackUri("/auth/callback?code=abc&state=xyz", redirectUri);
        assertEquals("/auth/callback", relative.getPath());
        assertEquals("code=abc&state=xyz", relative.getRawQuery());
    }

    @Test
    void parseCallbackUriSupportsQueryOnlyAndBlankValues() {
        String redirectUri = "http://localhost:1455/auth/callback";

        URI queryOnly = OAuthUrlCodec.parseCallbackUri("code=abc&state=xyz", redirectUri);
        assertEquals("/auth/callback", queryOnly.getPath());
        assertEquals("code=abc&state=xyz", queryOnly.getRawQuery());

        URI blank = OAuthUrlCodec.parseCallbackUri("   ", redirectUri);
        assertTrue(blank.toString().isEmpty());
    }
}
