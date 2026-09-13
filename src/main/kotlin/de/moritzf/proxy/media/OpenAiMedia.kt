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

    data class AdvertisedMediaModel(
        val localId: String,
        val providerId: String,
        val mode: String,
    )

    fun advertisedMediaModels(providerIds: Set<String>): List<AdvertisedMediaModel> {
        return buildList {
            if ("supergrok" in providerIds) {
                add(AdvertisedMediaModel("sg-grok-imagine-image", "supergrok", "image"))
            }
            if ("minimax" in providerIds) {
                add(AdvertisedMediaModel("mm-image-01", "minimax", "image"))
                add(AdvertisedMediaModel("mm-speech-2.6-turbo", "minimax", "audio"))
                add(AdvertisedMediaModel("mm-asr-1.0", "minimax", "audio"))
            }
            if ("zai" in providerIds) {
                add(AdvertisedMediaModel("za-glm-image", "zai", "image"))
                add(AdvertisedMediaModel("za-glm-asr-2512", "zai", "audio"))
            }
            if ("mistral" in providerIds) {
                add(AdvertisedMediaModel("mi-voxtral-mini-tts-2603", "mistral", "audio"))
                add(AdvertisedMediaModel("mi-voxtral-mini-latest", "mistral", "audio"))
            }
            if ("openai" in providerIds) {
                add(AdvertisedMediaModel("oa-gpt-4o-mini-tts", "openai", "audio"))
                add(AdvertisedMediaModel("oa-gpt-transcribe", "openai", "audio"))
            }
        }
    }

    fun advertisedMediaIds(providerIds: Set<String>): Set<String> {
        return advertisedMediaModels(providerIds).map { it.localId }.toSet()
    }

    fun isChatDiscoveryId(id: String): Boolean {
        val n = id.trim().lowercase()
        if (n.isEmpty()) return false
        return NON_CHAT_MARKERS.none { it in n }
    }

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

    private val NON_CHAT_MARKERS = listOf(
        "embed",
        "tts",
        "transcribe",
        "voxtral",
        "ocr",
        "moderation",
        "imagine",
        "asr",
    )
}
