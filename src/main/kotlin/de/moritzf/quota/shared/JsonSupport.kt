package de.moritzf.quota.shared

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.serializer

internal object JsonSupport {
    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    /**
     * Decodes one payload section leniently: a missing, null, or unparsable section yields null
     * instead of failing the whole payload, so the remaining quota sections stay usable.
     */
    fun <T> decodeSectionOrNull(element: JsonElement?, deserializer: DeserializationStrategy<T>): T? {
        val present = element?.takeUnless { it is JsonNull } ?: return null
        return runCatching { json.decodeFromJsonElement(deserializer, present) }.getOrNull()
    }

    /**
     * Decodes array items individually: unparsable items are dropped and anything that is not an
     * array yields an empty list, so one broken entry cannot break its siblings.
     */
    /** Display-only. Compact provider JSON becomes indented text so a settings viewer cannot grow sideways. */
    fun prettyResponse(text: String?): String {
        val raw = text ?: return ""
        val split = raw.indexOf("\n\n")
        if (raw.startsWith("Error:") && split >= 0) {
            return raw.substring(0, split + 2) + prettyJsonOrSame(raw.substring(split + 2))
        }
        return prettyJsonOrSame(raw)
    }

    private fun prettyJsonOrSame(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return text
        return runCatching {
            json.encodeToString(serializer<JsonElement>(), json.parseToJsonElement(trimmed))
        }.getOrDefault(text)
    }

    fun <T> decodeListItemsLeniently(element: JsonElement?, deserializer: DeserializationStrategy<T>): List<T> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            runCatching { json.decodeFromJsonElement(deserializer, item) }.getOrNull()
        }
    }
}
