package de.moritzf.quota.idea.auth

import de.moritzf.quota.openai.dto.OpenAiAuthorizationDto
import de.moritzf.quota.shared.JsonSupport
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/** Utilities for extracting account metadata from JWT tokens. */
object QuotaTokenUtil {
    @OptIn(ExperimentalEncodingApi::class)
    @JvmStatic
    fun extractChatGptAccountId(token: String?): String? {
        if (token.isNullOrBlank()) {
            return null
        }

        val parts = token.split(".")
        if (parts.size < 2) {
            return null
        }

        return try {
            val decoded =
                Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(parts[1])
            val payload =
                JsonSupport.json.parseToJsonElement(String(decoded, Charsets.UTF_8)).jsonObject
            val openAiAuthNode = payload["https://api.openai.com/auth"]
            if (openAiAuthNode == null || openAiAuthNode is JsonNull) {
                null
            } else {
                JsonSupport.json
                    .decodeFromJsonElement<OpenAiAuthorizationDto>(openAiAuthNode)
                    .chatgptAccountId
                    ?.trim()
                    ?.takeUnless { it.isBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    @JvmStatic
    fun extractGoogleEmail(token: String?): String? {
        val payload = extractPayload(token) ?: return null
        return payload["email"]?.toString()?.removeSurrounding("\"")?.takeUnless { it.isBlank() }
    }

    @OptIn(ExperimentalEncodingApi::class)
    @JvmStatic
    fun extractGoogleHd(token: String?): String? {
        val payload = extractPayload(token) ?: return null
        return payload["hd"]?.toString()?.removeSurrounding("\"")?.takeUnless { it.isBlank() }
    }

    /**
     * Short, non-reversible identifier for a token, safe to write into idea.log. It lets a support
     * log show whether a token was rotated, reused, or replaced by another IDE process without ever
     * exposing the token itself.
     */
    @JvmStatic
    fun fingerprint(token: String?): String {
        val value = token?.takeUnless { it.isBlank() } ?: return "none"
        return try {
            val digest =
                MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            digest.take(4).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            "unavailable"
        }
    }

    @JvmStatic
    fun extractJwtExpiresAtMs(token: String?): Long? {
        val payload = extractPayload(token) ?: return null
        val seconds =
            payload["exp"]?.toString()?.removeSurrounding("\"")?.toLongOrNull() ?: return null
        return seconds.takeIf { it > 0 }?.times(1000L)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun extractPayload(token: String?): kotlinx.serialization.json.JsonObject? {
        if (token.isNullOrBlank()) return null
        val parts = token.split(".")
        if (parts.size < 2) return null
        return try {
            val decoded =
                Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(parts[1])
            JsonSupport.json.parseToJsonElement(String(decoded, Charsets.UTF_8)).jsonObject
        } catch (_: Exception) {
            null
        }
    }
}
