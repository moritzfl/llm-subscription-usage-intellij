package de.moritzf.quota.cursor

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Parses Cursor browser session cookies into API credentials.
 *
 * The `WorkosCursorSessionToken` cookie uses the format `userId::accessToken`
 * (URL-encoded as `userId%3A%3AaccessToken`). The access token is a JWT used as
 * Bearer auth against `https://api2.cursor.sh`.
 */
object CursorSessionTokenParser {
    const val COOKIE_NAME = "WorkosCursorSessionToken"
    private const val SEPARATOR = "::"

    fun extractAccessToken(sessionToken: String): String? {
        val trimmed = sessionToken.trim()
        if (trimmed.isBlank()) {
            return null
        }

        val decoded = decode(trimmed)
        val separatorIndex = decoded.indexOf(SEPARATOR)
        if (separatorIndex >= 0) {
            val token = decoded.substring(separatorIndex + SEPARATOR.length).trim()
            if (token.isNotBlank()) {
                return token
            }
        }

        return decoded.takeIf { it.startsWith("eyJ") }
    }

    fun extractUserId(sessionToken: String): String? {
        val decoded = decode(sessionToken.trim())
        val separatorIndex = decoded.indexOf(SEPARATOR)
        if (separatorIndex <= 0) {
            return null
        }
        return decoded.substring(0, separatorIndex).trim().takeIf { it.isNotBlank() }
    }

    fun buildCookieHeader(sessionToken: String): String {
        return "$COOKIE_NAME=${sessionToken.trim()}"
    }

    private fun decode(value: String): String {
        return runCatching {
            URLDecoder.decode(value, StandardCharsets.UTF_8)
        }.getOrDefault(value)
    }
}
