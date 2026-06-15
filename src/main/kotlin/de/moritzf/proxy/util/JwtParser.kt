package de.moritzf.proxy.util
import com.fasterxml.jackson.databind.JsonNode
import java.nio.charset.StandardCharsets
import java.util.Base64
object JwtParser {
    fun parseClaims(token: String?): JsonNode? {
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
            val node = Json.MAPPER.readTree(payload)
            node.takeIf { it.isObject }
        } catch (_: Exception) {
            null
        }
    }
    fun deriveAccountId(idToken: String?): String? {
        val claims = parseClaims(idToken) ?: return null
        val authClaim = claims.get("https://api.openai.com/auth")
        if (authClaim != null && authClaim.isObject) {
            val accountId = authClaim.get("chatgpt_account_id")
            if (accountId != null && accountId.isTextual && accountId.asText().isNotEmpty()) {
                return accountId.asText()
            }
        }
        return null
    }
}
