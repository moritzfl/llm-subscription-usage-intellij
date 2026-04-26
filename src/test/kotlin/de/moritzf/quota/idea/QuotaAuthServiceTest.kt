package de.moritzf.quota.idea

import de.moritzf.quota.idea.auth.QuotaAuthService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for OAuth callback URI and query parsing helpers.
 */
class QuotaAuthServiceTest {
    @Test
    fun parseUriAcceptsFullCallbackUrl() {
        val uri = QuotaAuthService.parseUri("http://127.0.0.1:1455/auth/callback?code=abc&state=xyz")
        assertEquals("/auth/callback", uri.path)
        assertEquals("code=abc&state=xyz", uri.rawQuery)
    }

    @Test
    fun parseUriAcceptsRelativeCallbackUrl() {
        val uri = QuotaAuthService.parseUri("/auth/callback?code=abc&state=xyz")
        assertEquals("/auth/callback", uri.path)
        assertEquals("code=abc&state=xyz", uri.rawQuery)
    }

    @Test
    fun parseUriAcceptsQueryOnly() {
        val uri = QuotaAuthService.parseUri("code=abc&state=xyz")
        assertEquals("/auth/callback", uri.path)
        assertEquals("code=abc&state=xyz", uri.rawQuery)
    }

    @Test
    fun parseQueryDecodesValues() {
        val params = QuotaAuthService.parseQuery("code=abc%20123&state=xy%2Bz")
        assertEquals("abc 123", params["code"])
        assertEquals("xy+z", params["state"])
    }

    @Test
    fun parseQueryEmptyReturnsEmptyMap() {
        val params = QuotaAuthService.parseQuery("")
        assertTrue(params.isEmpty())
    }
}
