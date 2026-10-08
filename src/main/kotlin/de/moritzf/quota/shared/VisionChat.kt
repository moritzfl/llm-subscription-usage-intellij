package de.moritzf.quota.shared

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * OpenAI-compatible chat vision request pieces shared by subscription API-key providers (Mistral,
 * Z.ai, Ollama, Kimi). Providers only add auth, endpoint, and error mapping.
 */
internal object VisionChat {
    /**
     * Image data URL plus raw base64 (Anthropic needs the raw data), or null when the file is not
     * an image.
     */
    fun imageDataUrl(path: Path): Pair<String, String>? {
        if (!Files.isRegularFile(path)) return null
        val bytes = Files.readAllBytes(path)
        val mime = imageMimeType(path, bytes) ?: return null
        val base64 = Base64.getEncoder().encodeToString(bytes)
        return "data:$mime;base64,$base64" to base64
    }

    /**
     * Public URL passthrough or local-file data URL as one OpenAI-compatible image_url content
     * part.
     */
    fun chatImageContent(imageUrl: String?, localFile: Path?): JsonObject? {
        val url = imageUrl?.trim().orEmpty()
        if (url.isNotEmpty()) {
            return buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", url) })
            }
        }
        val dataUrl = localFile?.let { imageDataUrl(it)?.first } ?: return null
        return buildJsonObject {
            put("type", "image_url")
            put("image_url", buildJsonObject { put("url", dataUrl) })
        }
    }

    fun chatRequestJson(model: String, imageContent: JsonObject, prompt: String): String {
        return buildJsonObject {
            put("model", model)
            put("stream", false)
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            add(imageContent)
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", prompt)
                                }
                            )
                        }
                    }
                )
            }
        }
            .toString()
    }

    /** First chat choice's message content, as a plain string or text parts. */
    fun chatAnswer(body: String): String? {
        val root =
            runCatching { JsonSupport.json.parseToJsonElement(body) }.getOrNull() as? JsonObject
                ?: return null
        val message =
            ((root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message")
                as? JsonObject ?: return null
        return when (val content = message["content"]) {
            is JsonPrimitive -> content.contentOrNull?.takeIf { it.isNotBlank() }
            is JsonArray ->
                content
                    .mapNotNull { (it as? JsonObject)?.get("text") as? JsonPrimitive }
                    .mapNotNull { it.contentOrNull }
                    .joinToString("")
                    .takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun imageMimeType(path: Path, bytes: ByteArray): String? {
        if (bytes.size >= 8 && bytes[0] == 0x89.toByte()) return "image/png"
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte())
            return "image/jpeg"
        if (
            bytes.size >= 12 &&
                bytes.decodeToString(0, 4) == "RIFF" &&
                bytes.decodeToString(8, 12) == "WEBP"
        ) {
            return "image/webp"
        }
        if (bytes.size >= 6 && (bytes.decodeToString(0, 3) == "GIF")) return "image/gif"
        return when (path.fileName.toString().substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg",
            "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> null
        }
    }
}
