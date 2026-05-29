package de.moritzf.quota.cursor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CursorSessionTokenParserTest {
    @Test
    fun extractAccessTokenFromDecodedSessionToken() {
        val token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.payload.signature"
        val sessionToken = "user_01ABC::$token"

        assertEquals(token, CursorSessionTokenParser.extractAccessToken(sessionToken))
        assertEquals("user_01ABC", CursorSessionTokenParser.extractUserId(sessionToken))
    }

    @Test
    fun extractAccessTokenFromUrlEncodedSessionToken() {
        val token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.payload.signature"
        val sessionToken = "user_01ABC%3A%3A$token"

        assertEquals(token, CursorSessionTokenParser.extractAccessToken(sessionToken))
        assertEquals("user_01ABC", CursorSessionTokenParser.extractUserId(sessionToken))
    }

    @Test
    fun extractAccessTokenFromRawJwt() {
        val token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.payload.signature"

        assertEquals(token, CursorSessionTokenParser.extractAccessToken(token))
        assertNull(CursorSessionTokenParser.extractUserId(token))
    }

    @Test
    fun buildCookieHeaderUsesCookieName() {
        assertEquals(
            "${CursorSessionTokenParser.COOKIE_NAME}=user_01ABC%3A%3AeyJ",
            CursorSessionTokenParser.buildCookieHeader("user_01ABC%3A%3AeyJ"),
        )
    }
}
