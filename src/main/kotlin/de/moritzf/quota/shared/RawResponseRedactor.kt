package de.moritzf.quota.shared

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

internal object RawResponseRedactor {
    private const val REDACTED = "[REDACTED]"
    private val exactSensitiveNames =
        setOf(
            "authorization",
            "proxy_authorization",
            "cookie",
            "set_cookie",
            "access_token",
            "refresh_token",
            "id_token",
            "api_key",
            "apikey",
            "password",
            "secret",
        )
    private val tokenCountNames =
        setOf(
            "total_tokens",
            "input_tokens",
            "output_tokens",
            "prompt_tokens",
            "completion_tokens",
            "cached_tokens",
            "reasoning_tokens",
        )
    private val sensitiveNameFragments = listOf("secret", "password", "cookie")

    fun redact(raw: String?): String? {
        if (raw.isNullOrBlank()) return raw
        val element =
            runCatching { JsonSupport.json.parseToJsonElement(raw) }.getOrNull() ?: return raw
        return runCatching { redact(element).toString() }.getOrDefault(raw)
    }

    fun redact(
        element: JsonElement,
        extraSensitiveNames: Set<String> = emptySet(),
        secret: String? = null,
    ): JsonElement =
        when (element) {
            is JsonObject ->
                buildJsonObject {
                    element.forEach { (key, value) ->
                        put(
                            key,
                            if (isSensitiveName(key, extraSensitiveNames)) JsonPrimitive(REDACTED)
                            else redact(value, extraSensitiveNames, secret),
                        )
                    }
                }
            is JsonArray ->
                buildJsonArray { element.forEach { add(redact(it, extraSensitiveNames, secret)) } }
            is JsonPrimitive ->
                if (element.isString && !secret.isNullOrBlank())
                    JsonPrimitive(element.content.replace(secret, REDACTED))
                else element
        }

    private fun isSensitiveName(name: String, extraSensitiveNames: Set<String>): Boolean {
        val normalized =
            name.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase().replace('-', '_')
        return normalized in exactSensitiveNames ||
            normalized in extraSensitiveNames ||
            normalized.endsWith("_key") ||
            normalized.endsWith("key") && normalized.startsWith("api") ||
            normalized.endsWith("_token") && normalized !in tokenCountNames ||
            sensitiveNameFragments.any { normalized.contains(it) }
    }
}
