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
import kotlinx.serialization.json.putJsonObject

internal enum class NativePdfRoute {
    RESPONSES,
    CHAT,
    ANTHROPIC,
}

/** Request bodies and response text for a provider that accepts a PDF file part. No model catalog. */
internal object NativePdfDocument {
    const val PROMPT = "Convert this document to markdown. Include the visible text. Return only the markdown."

    fun requestJson(route: NativePdfRoute, model: String, filename: String, dataUrl: String, base64: String): String {
        return when (route) {
            NativePdfRoute.RESPONSES -> responsesBody(model, filename, dataUrl)
            NativePdfRoute.CHAT -> chatBody(model, filename, dataUrl)
            NativePdfRoute.ANTHROPIC -> anthropicBody(model, base64)
        }
    }

    fun dataUrl(path: Path): Pair<String, String> {
        val bytes = Files.readAllBytes(path)
        val encoded = Base64.getEncoder().encodeToString(bytes)
        return "data:application/pdf;base64,$encoded" to encoded
    }

    fun convert(
        route: NativePdfRoute,
        model: String,
        source: Path,
        output: Path?,
        post: (String) -> String,
    ): String {
        val (dataUrl, base64) = dataUrl(source)
        val body = post(requestJson(route, model, source.fileName.toString(), dataUrl, base64))
        val markdown = extractText(body)
        if (markdown.isBlank()) error("Provider returned no markdown.")
        return DocumentMarkdown.resultJson(markdown, output ?: DocumentMarkdown.defaultOutput(source))
    }

    fun extractText(body: String): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("data:") || trimmed.contains("\ndata:")) return extractSse(trimmed)
        val json = runCatching { JsonSupport.json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
            ?: return trimmed
        providerError(json)?.let { error(it) }
        responsesText(json)?.let { return it }
        chatText(json)?.let { return it }
        anthropicText(json)?.let { return it }
        return trimmed
    }

    private fun responsesBody(model: String, filename: String, dataUrl: String): String = buildJsonObject {
        put("model", model)
        put("stream", false)
        putJsonArray("input") {
            add(buildJsonObject {
                put("type", "message")
                put("role", "user")
                putJsonArray("content") {
                    add(buildJsonObject {
                        put("type", "input_file")
                        put("filename", filename)
                        put("file_data", dataUrl)
                    })
                    add(buildJsonObject {
                        put("type", "input_text")
                        put("text", PROMPT)
                    })
                }
            })
        }
    }.toString()

    private fun chatBody(model: String, filename: String, dataUrl: String): String = buildJsonObject {
        put("model", model)
        put("stream", false)
        putJsonArray("messages") {
            add(buildJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    add(buildJsonObject {
                        put("type", "file")
                        putJsonObject("file") {
                            put("filename", filename)
                            put("file_data", dataUrl)
                        }
                    })
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", PROMPT)
                    })
                }
            })
        }
    }.toString()

    private fun anthropicBody(model: String, base64: String): String = buildJsonObject {
        put("model", model)
        put("max_tokens", 4096)
        put("stream", false)
        putJsonArray("messages") {
            add(buildJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    add(buildJsonObject {
                        put("type", "document")
                        putJsonObject("source") {
                            put("type", "base64")
                            put("media_type", "application/pdf")
                            put("data", base64)
                        }
                    })
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", PROMPT)
                    })
                }
            })
        }
    }.toString()

    private fun extractSse(body: String): String {
        val text = StringBuilder()
        body.lineSequence().forEach { line ->
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty() || data == "[DONE]" || !data.startsWith("{")) return@forEach
            val event = runCatching { JsonSupport.json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return@forEach
            val type = (event["type"] as? JsonPrimitive)?.contentOrNull
            when (type) {
                "response.output_text.delta" -> text.append((event["delta"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                "content_block_delta" -> {
                    val delta = event["delta"] as? JsonObject
                    text.append((delta?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty())
                }
            }
            chatText(event)?.let { if (text.isEmpty()) text.append(it) }
        }
        return text.toString()
    }

    private fun responsesText(json: JsonObject): String? {
        val output = json["output"] as? JsonArray ?: return null
        val parts = output.mapNotNull { item ->
            val content = (item as? JsonObject)?.get("content") as? JsonArray ?: return@mapNotNull null
            content.mapNotNull { part ->
                val obj = part as? JsonObject ?: return@mapNotNull null
                (obj["text"] as? JsonPrimitive)?.contentOrNull
            }.joinToString("")
        }
        return parts.joinToString("\n").takeIf { it.isNotBlank() }
    }

    private fun providerError(json: JsonObject): String? {
        val error = json["error"] ?: return null
        return when (error) {
            is JsonPrimitive -> error.contentOrNull?.takeIf { it.isNotBlank() }
            is JsonObject -> (error["message"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun chatText(json: JsonObject): String? {
        val choices = json["choices"] as? JsonArray ?: return null
        val message = (choices.firstOrNull() as? JsonObject)?.get("message") as? JsonObject ?: return null
        return when (val content = message["content"]) {
            is JsonPrimitive -> content.contentOrNull
            is JsonArray -> content.mapNotNull { part ->
                (part as? JsonObject)?.get("text") as? JsonPrimitive
            }.mapNotNull { it.contentOrNull }.joinToString("").takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun anthropicText(json: JsonObject): String? {
        val content = json["content"] as? JsonArray ?: return null
        return content.mapNotNull { part ->
            val obj = part as? JsonObject ?: return@mapNotNull null
            if ((obj["type"] as? JsonPrimitive)?.contentOrNull != "text") return@mapNotNull null
            (obj["text"] as? JsonPrimitive)?.contentOrNull
        }.joinToString("").takeIf { it.isNotBlank() }
    }
}
