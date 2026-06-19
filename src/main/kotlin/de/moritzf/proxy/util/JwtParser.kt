package de.moritzf.proxy.util
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.charset.StandardCharsets
import java.util.Base64
object JwtParser {
    fun parseClaims(token: String?): JsonObject? {
        if (token == null || !token.contains('.')) {
            return null
        }
        val parts = token.split('.')
        if (parts.size != 3 || parts[1].isEmpty()) {
            return null
        }
        return try {
            var padded = parts[1]
            val remainder = padded.length % 4
            if (remainder > 0) {
                padded += "=".repeat(4 - remainder)
            }
            val decoded = Base64.getUrlDecoder().decode(padded)
            val payload = String(decoded, StandardCharsets.UTF_8)
            Json.INSTANCE.parseToJsonElement(payload) as? JsonObject
        } catch (_: Exception) {
            null
        }
    }
    fun deriveAccountId(idToken: String?): String? {
        val claims = parseClaims(idToken) ?: return null
        val authClaim = claims["https://api.openai.com/auth"]
        if (authClaim is JsonObject) {
            val accountId = authClaim["chatgpt_account_id"]
            if (accountId is JsonPrimitive && accountId.isString && accountId.content.isNotEmpty()) {
                return accountId.content
            }
        }
        return null
    }
}