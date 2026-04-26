package de.moritzf.quota.idea.auth

import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.openai.dto.OpenAiAuthorizationDto
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Utilities for extracting OpenAI account metadata from JWT tokens.
 */
object QuotaTokenUtil {
    @OptIn(ExperimentalEncodingApi::class)
    @JvmStatic
    fun extractChatGptAccountId(token: String?): String? {
        if (token.isNullOrBlank()) {
            return null
        }

        val parts = token.split('.')
        if (parts.size < 2) {
            return null
        }

        return try {
            val decoded = Base64.UrlSafe
                .withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
                .decode(parts[1])
            val payload = JsonSupport.json.parseToJsonElement(String(decoded, Charsets.UTF_8)).jsonObject
            val openAiAuthNode = payload["https://api.openai.com/auth"]
            if (openAiAuthNode == null || openAiAuthNode is JsonNull) {
                null
            } else {
                JsonSupport.json.decodeFromJsonElement<OpenAiAuthorizationDto>(openAiAuthNode)
                    .chatgptAccountId
                    ?.trim()
                    ?.takeUnless { it.isBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }
}
