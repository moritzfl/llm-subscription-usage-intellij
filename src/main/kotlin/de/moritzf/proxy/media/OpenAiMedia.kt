package de.moritzf.proxy.media

import de.moritzf.quota.shared.HttpJsonUrls
import kotlin.time.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal object OpenAiMedia {
    const val IMAGES_PATH = "/images/generations"
    const val SPEECH_PATH = "/audio/speech"
    const val TRANSCRIPTIONS_PATH = "/audio/transcriptions"

    fun providerIdForModel(model: String): String? {
        val id = model.trim()
        return when {
            id.startsWith("sg-") -> "supergrok"
            id.startsWith("mm-") -> "minimax"
            id.startsWith("za-") -> "zai"
            id.startsWith("mi-") -> "mistral"
            id.startsWith("oa-") -> "openai"
            else -> null
        }
    }

    fun upstreamModel(model: String): String {
        val id = model.trim()
        val prefix = listOf("sg-", "mm-", "za-", "mi-", "oa-").firstOrNull { id.startsWith(it) } ?: return id
        return id.removePrefix(prefix).ifBlank { id }
    }

    fun rejectB64(format: String?): String? {
        val value = format?.trim()?.lowercase() ?: return null
        if (value == "b64_json" || value == "b64") {
            return "response_format=b64_json is not supported. Use url."
        }
        return null
    }

    fun imageUrlResponse(url: String, created: Long = Clock.System.now().epochSeconds): JsonObject {
        return buildJsonObject {
            put("created", JsonPrimitive(created))
            put(
                "data",
                buildJsonArray {
                    add(buildJsonObject { put("url", JsonPrimitive(url)) })
                },
            )
        }
    }

    fun imageUrlFromProviderJson(body: String): String? {
        HttpJsonUrls.first(body)?.let { return it }
        val root = runCatching {
            de.moritzf.quota.shared.JsonSupport.json.parseToJsonElement(body)
        }.getOrNull() as? JsonObject ?: return null
        return (root["url"] as? JsonPrimitive)?.contentOrNull
    }

    fun speechContentType(format: String): String {
        return when (format.trim().lowercase()) {
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "opus" -> "audio/opus"
            "pcm" -> "audio/pcm"
            else -> "audio/mpeg"
        }
    }

    fun requestedImageFormat(body: JsonObject): String? {
        return body["response_format"]?.jsonPrimitive?.contentOrNull
    }
}
