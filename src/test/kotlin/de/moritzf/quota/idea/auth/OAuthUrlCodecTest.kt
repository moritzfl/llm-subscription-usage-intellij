package de.moritzf.quota.idea.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for OAuth URL encoding and callback/query parsing helpers.
 */
class OAuthUrlCodecTest {
    @Test
    fun formEncodeEncodesReservedCharacters() {
        val params = linkedMapOf(
            "state" to "a b+c",
            "redirect_uri" to "http://localhost:1455/auth/callback?x=1&y=2",
        )

        val encoded = OAuthUrlCodec.formEncode(params)

        assertEquals(
            "state=a+b%2Bc&redirect_uri=http%3A%2F%2Flocalhost%3A1455%2Fauth%2Fcallback%3Fx%3D1%26y%3D2",
            encoded,
        )
    }

    @Test
    fun formEncodeSupportsVarargPairs() {
        val encoded = OAuthUrlCodec.formEncode("grant_type" to "refresh token", "client_id" to "a+b")

        assertEquals("grant_type=refresh+token&client_id=a%2Bb", encoded)
    }

    @Test
    fun parseQueryDecodesValuesAndSkipsInvalidPairs() {
        val params = OAuthUrlCodec.parseQuery("code=abc%20123&state=xy%2Bz&invalid&=skip&k=v")

        assertEquals("abc 123", params["code"])
        assertEquals("xy+z", params["state"])
        assertEquals("v", params["k"])
        assertEquals(3, params.size)
    }

    @Test
    fun parseCallbackUriAcceptsAbsoluteAndRelativeForms() {
        val redirectUri = "http://localhost:1455/auth/callback"

        val absolute = OAuthUrlCodec.parseCallbackUri("https://example.com/auth/callback?code=abc", redirectUri)
        assertEquals("https://example.com/auth/callback?code=abc", absolute.toString())

        val relative = OAuthUrlCodec.parseCallbackUri("/auth/callback?code=abc&state=xyz", redirectUri)
        assertEquals("/auth/callback", relative.path)
        assertEquals("code=abc&state=xyz", relative.rawQuery)
    }

    @Test
    fun parseCallbackUriSupportsQueryOnlyAndBlankValues() {
        val redirectUri = "http://localhost:1455/auth/callback"

        val queryOnly = OAuthUrlCodec.parseCallbackUri("code=abc&state=xyz", redirectUri)
        assertEquals("/auth/callback", queryOnly.path)
        assertEquals("code=abc&state=xyz", queryOnly.rawQuery)

        val blank = OAuthUrlCodec.parseCallbackUri("   ", redirectUri)
        assertTrue(blank.toString().isEmpty())
    }
}
