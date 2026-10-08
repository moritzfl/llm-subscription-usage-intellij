package de.moritzf.quota.shared

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal object HttpJsonUrls {
    fun first(body: String): String? {
        val root =
            runCatching { JsonSupport.json.parseToJsonElement(body) }.getOrNull() ?: return null
        return first(root)
    }

    private fun first(element: JsonElement): String? {
        return when (element) {
            is JsonObject -> {
                listOf("url", "video_url", "file_url").firstNotNullOfOrNull { key ->
                    (element[key] as? JsonPrimitive)?.contentOrNull?.takeIf(::isHttp)
                } ?: element.values.firstNotNullOfOrNull(::first)
            }
            is JsonArray -> element.firstNotNullOfOrNull(::first)
            else -> null
        }
    }

    private fun isHttp(value: String): Boolean {
        return value.startsWith("https://") || value.startsWith("http://")
    }
}
