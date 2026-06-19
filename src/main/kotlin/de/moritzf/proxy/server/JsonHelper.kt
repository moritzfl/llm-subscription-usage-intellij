package de.moritzf.proxy.server

import de.moritzf.proxy.util.Json
import io.javalin.http.Context
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

object JsonHelper {
    val JSON: kotlinx.serialization.json.Json = Json.INSTANCE
    const val SSE_CONTENT_TYPE: String = "text/event-stream; charset=utf-8"
    const val JSON_CONTENT_TYPE: String = "application/json; charset=utf-8"

    fun toJsonResponse(ctx: Context, body: Any?) {
        toJsonResponse(ctx, body, 200)
    }

    fun toJsonResponse(ctx: Context, body: Any?, status: Int) {
        ctx.status(status)
        ctx.contentType(JSON_CONTENT_TYPE)
        try {
            val json = when (body) {
                is JsonElement -> JSON.encodeToString(JsonElement.serializer(), body)
                is Map<*, *> -> {
                    val element = mapToJsonElement(body)
                    JSON.encodeToString(JsonElement.serializer(), element)
                }
                is List<*> -> {
                    val element = listToJsonElement(body)
                    JSON.encodeToString(JsonElement.serializer(), element)
                }
                else -> body.toString()
            }
            AccessLogFields.responseBytes(ctx, json.toByteArray(StandardCharsets.UTF_8).size.toLong())
            ctx.result(json)
        } catch (_: Exception) {
            val fallback = "{}"
            AccessLogFields.responseBytes(ctx, fallback.toByteArray(StandardCharsets.UTF_8).size.toLong())
            ctx.result(fallback)
        }
    }

    fun toJsonResponse(ctx: Context, body: JsonElement, status: Int) {
        ctx.status(status)
        ctx.contentType(JSON_CONTENT_TYPE)
        val json = JSON.encodeToString(JsonElement.serializer(), body)
        AccessLogFields.responseBytes(ctx, json.toByteArray(StandardCharsets.UTF_8).size.toLong())
        ctx.result(json)
    }

    fun toErrorResponse(ctx: Context, message: String?) {
        toErrorResponse(ctx, message, 400, "invalid_request_error")
    }

    fun toErrorResponse(ctx: Context, message: String?, status: Int, type: String) {
        val root = buildJsonObject {
            putJsonObject("error") {
                put("message", message)
                put("type", type)
                put("param", JsonNull)
                put("code", status.toString())
            }
        }
        toJsonResponse(ctx, root, status)
    }

    /** Builds an OpenAI-shaped error object: `{message, type, param, code}`. */
    fun errorObject(message: String?, type: String?, code: String?): JsonObject = buildJsonObject {
        put("message", message)
        put("type", type)
        put("param", JsonNull)
        put("code", code)
    }

    fun readUtf8Body(input: InputStream): String = String(input.readAllBytes(), StandardCharsets.UTF_8)

    fun toUsage(usageNode: JsonElement?): JsonObject = buildJsonObject {
        if (usageNode == null || usageNode !is JsonObject) {
            put("prompt_tokens", 0)
            put("completion_tokens", 0)
            put("total_tokens", 0)
            return@buildJsonObject
        }
        val promptTokens = usageNode.intPath("input_tokens", 0)
        val completionTokens = usageNode.intPath("output_tokens", 0)
        put("prompt_tokens", promptTokens)
        put("completion_tokens", completionTokens)
        put("total_tokens", promptTokens + completionTokens)
        val cachedTokens = usageNode
            .pathOrNull("input_tokens_details")?.intPath("cached_tokens", -1)
        if (cachedTokens != null && cachedTokens >= 0) {
            putJsonObject("prompt_tokens_details") {
                put("cached_tokens", cachedTokens)
            }
        }
        val reasoningTokens = usageNode
            .pathOrNull("output_tokens_details")?.intPath("reasoning_tokens", -1)
        if (reasoningTokens != null && reasoningTokens >= 0) {
            putJsonObject("completion_tokens_details") {
                put("reasoning_tokens", reasoningTokens)
            }
        }
    }

    /**
     * Normalizes any upstream error payload to the OpenAI `{"error":{...}}` envelope,
     * which is the only shape OpenAI-compatible clients parse. The Codex backend frequently
     * answers `{"detail": "..."}` instead. When [overrideType] is set, e.g.
     * `insufficient_quota`, it replaces the error type and code so clients classify the
     * failure correctly.
     */
    fun toUpstreamErrorBody(raw: String?, status: Int, overrideType: String?): String {
        var parsed: JsonObject? = null
        if (!raw.isNullOrBlank()) {
            try {
                val candidate = JSON.parseToJsonElement(raw)
                if (candidate is JsonObject) {
                    parsed = candidate
                }
            } catch (_: Exception) {
            }
        }
        if (parsed != null && overrideType == null) {
            val error = parsed.pathOrNull("error")
            val message = error?.pathOrNull("message")
            if (error is JsonObject && message is JsonPrimitive && message.isString) {
                return raw.orEmpty()
            }
        }
        val message = extractErrorMessage(parsed, raw)
        val root = buildJsonObject {
            putJsonObject("error") {
                put("message", message)
                put("type", overrideType ?: "upstream_error")
                put("code", overrideType ?: status.toString())
            }
        }
        return try {
            JSON.encodeToString(JsonObject.serializer(), root)
        } catch (_: Exception) {
            "{\"error\":{\"message\":\"Upstream error\",\"type\":\"upstream_error\"}}"
        }
    }

    fun setSseHeaders(ctx: Context) {
        ctx.contentType(SSE_CONTENT_TYPE)
        ctx.header("Cache-Control", "no-cache, no-transform")
        ctx.header("Connection", "keep-alive")
        ctx.header("X-Accel-Buffering", "no")
    }

    fun encodeToString(element: JsonElement): String = JSON.encodeToString(JsonElement.serializer(), element)

    fun parseToJsonElement(input: String): JsonElement = JSON.parseToJsonElement(input)

    fun parseToJsonElementOrNull(input: String): JsonElement? = try {
        JSON.parseToJsonElement(input)
    } catch (_: Exception) {
        null
    }

    private fun mapToJsonElement(map: Map<*, *>): JsonElement = buildJsonObject {
        map.forEach { (key, value) ->
            @Suppress("UNCHECKED_CAST")
            when (value) {
                null -> put(key.toString(), JsonNull)
                is String -> put(key.toString(), value)
                is Number -> put(key.toString(), JsonPrimitive(value))
                is Boolean -> put(key.toString(), value)
                is Map<*, *> -> put(key.toString(), mapToJsonElement(value))
                is List<*> -> put(key.toString(), listToJsonElement(value))
                is JsonElement -> put(key.toString(), value)
                else -> put(key.toString(), value.toString())
            }
        }
    }

    private fun listToJsonElement(list: List<*>): JsonElement = buildJsonArray {
        list.forEach { value ->
            @Suppress("UNCHECKED_CAST")
            when (value) {
                null -> add(JsonNull)
                is String -> add(JsonPrimitive(value))
                is Number -> add(JsonPrimitive(value))
                is Boolean -> add(JsonPrimitive(value))
                is Map<*, *> -> add(mapToJsonElement(value))
                is List<*> -> add(listToJsonElement(value))
                is JsonElement -> add(value)
                else -> add(JsonPrimitive(value.toString()))
            }
        }
    }

    private fun extractErrorMessage(parsed: JsonObject?, raw: String?): String {
        if (parsed != null) {
            val nested = parsed.pathOrNull("error")?.pathOrNull("message")
            if (nested is JsonPrimitive && nested.isString && nested.content.isNotBlank()) {
                return nested.content
            }
            for (field in listOf("detail", "message")) {
                val value = parsed[field]
                if (value is JsonPrimitive && value.isString && value.content.isNotBlank()) {
                    return value.content
                }
            }
        }
        return raw?.takeIf { it.isNotBlank() }?.trim() ?: "Upstream error"
    }
}

/**
 * Extension helpers for safe navigation of kotlinx.serialization JsonElement trees,
 * replacing Jackson's `path().asText()/asInt()/asLong()/asBoolean()` patterns.
 */
fun JsonElement?.pathOrNull(key: String): JsonElement? {
    if (this !is JsonObject) return null
    return this[key]
}

fun JsonElement?.stringPath(key: String, default: String = ""): String {
    val el = this.pathOrNull(key) ?: return default
    return if (el is JsonPrimitive && el.isString) el.content else default
}

fun JsonElement?.stringPathOrNull(key: String): String? {
    val el = this.pathOrNull(key) ?: return null
    return if (el is JsonPrimitive && el.isString) el.content else null
}

fun JsonElement?.intPath(key: String, default: Int = 0): Int {
    val el = this.pathOrNull(key) ?: return default
    return (el as? JsonPrimitive)?.intOrNull ?: default
}

fun JsonElement?.longPath(key: String, default: Long = 0L): Long {
    val el = this.pathOrNull(key) ?: return default
    return (el as? JsonPrimitive)?.longOrNull ?: default
}

fun JsonElement?.booleanPath(key: String, default: Boolean = false): Boolean {
    val el = this.pathOrNull(key) ?: return default
    val prim = el as? JsonPrimitive ?: return default
    return prim.content == "true" || prim.contentOrNull == "true"
}

fun JsonElement?.isArray(): Boolean = this is JsonArray

fun JsonElement?.isObject(): Boolean = this is JsonObject

fun JsonElement?.isTextual(): Boolean = this is JsonPrimitive && this.isString

fun JsonElement?.isNotNull(): Boolean = this != null && this !is JsonNull

val JsonElement?.textOrNull: String?
    get() = if (this is JsonPrimitive && isString) content else null

val JsonElement?.text: String
    get() = if (this is JsonPrimitive) content else ""

fun JsonObject.hasKey(key: String): Boolean = this[key] != null && this[key] !is JsonNull

fun JsonObject.hasNonNull(key: String): Boolean = this[key] != null && this[key] !is JsonNull

fun JsonElement?.deepCopy(): JsonElement? {
    // kotlinx.serialization JsonElement is already immutable, so a "copy" is just itself.
    return this
}

fun JsonObject.put(key: String, value: JsonElement): JsonObject {
    return JsonObject(this.toMutableMap().apply { this[key] = value })
}

fun JsonObject.remove(key: String): JsonObject {
    return JsonObject(this.toMutableMap().apply { this.remove(key) })
}

fun JsonArray.addElements(elements: Iterable<JsonElement>): JsonArray {
    return JsonArray(this + elements)
}