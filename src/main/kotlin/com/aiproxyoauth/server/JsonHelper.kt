package com.aiproxyoauth.server

import com.aiproxyoauth.util.Json
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.javalin.http.Context
import java.nio.charset.StandardCharsets

object JsonHelper {
    /** Shared mapper: alias to [Json.MAPPER]. */
    @JvmField
    val MAPPER: ObjectMapper = Json.MAPPER

    const val SSE_CONTENT_TYPE: String = "text/event-stream; charset=utf-8"
    const val JSON_CONTENT_TYPE: String = "application/json; charset=utf-8"

    @JvmStatic
    fun toJsonResponse(ctx: Context, body: Any?) {
        toJsonResponse(ctx, body, 200)
    }

    @JvmStatic
    fun toJsonResponse(ctx: Context, body: Any?, status: Int) {
        ctx.status(status)
        ctx.contentType(JSON_CONTENT_TYPE)
        try {
            val json = Json.MAPPER.writeValueAsString(body)
            AccessLogFields.responseBytes(ctx, json.toByteArray(StandardCharsets.UTF_8).size.toLong())
            ctx.result(json)
        } catch (_: Exception) {
            val fallback = "{}"
            AccessLogFields.responseBytes(ctx, fallback.toByteArray(StandardCharsets.UTF_8).size.toLong())
            ctx.result(fallback)
        }
    }

    @JvmStatic
    fun toErrorResponse(ctx: Context, message: String?) {
        toErrorResponse(ctx, message, 400, "invalid_request_error")
    }

    @JvmStatic
    fun toErrorResponse(ctx: Context, message: String?, status: Int, type: String) {
        val root = Json.MAPPER.createObjectNode()
        root.set<ObjectNode>("error", errorObject(message, type, status.toString()))
        toJsonResponse(ctx, root, status)
    }

    /** Builds an OpenAI-shaped error object: `{message, type, param, code}`. */
    @JvmStatic
    fun errorObject(message: String?, type: String?, code: String?): ObjectNode {
        val error = Json.MAPPER.createObjectNode()
        error.put("message", message)
        error.put("type", type)
        error.putNull("param")
        error.put("code", code)
        return error
    }

    @JvmStatic
    fun mapFinishReason(finishReason: String?): String? {
        return when (finishReason) {
            null -> null
            "stop" -> "stop"
            "length", "max_output_tokens" -> "length"
            "tool-calls", "tool_calls" -> "tool_calls"
            "content-filter", "content_filter" -> "content_filter"
            else -> null
        }
    }

    @JvmStatic
    fun toUsage(usageNode: JsonNode?): ObjectNode {
        val usage = Json.MAPPER.createObjectNode()
        if (usageNode == null || !usageNode.isObject) {
            usage.put("prompt_tokens", 0)
            usage.put("completion_tokens", 0)
            usage.put("total_tokens", 0)
            return usage
        }
        val promptTokens = usageNode.path("input_tokens").asInt(0)
        val completionTokens = usageNode.path("output_tokens").asInt(0)
        usage.put("prompt_tokens", promptTokens)
        usage.put("completion_tokens", completionTokens)
        usage.put("total_tokens", promptTokens + completionTokens)

        val cachedTokens = usageNode.path("input_tokens_details").path("cached_tokens").asInt(-1)
        if (cachedTokens >= 0) {
            val promptDetails = Json.MAPPER.createObjectNode()
            promptDetails.put("cached_tokens", cachedTokens)
            usage.set<ObjectNode>("prompt_tokens_details", promptDetails)
        }

        val reasoningTokens = usageNode.path("output_tokens_details").path("reasoning_tokens").asInt(-1)
        if (reasoningTokens >= 0) {
            val completionDetails = Json.MAPPER.createObjectNode()
            completionDetails.put("reasoning_tokens", reasoningTokens)
            usage.set<ObjectNode>("completion_tokens_details", completionDetails)
        }

        return usage
    }

    @JvmStatic
    fun toUpstreamErrorBody(raw: String?, status: Int): String {
        return toUpstreamErrorBody(raw, status, null)
    }

    /**
     * Normalizes any upstream error payload to the OpenAI `{"error":{...}}` envelope,
     * which is the only shape OpenAI-compatible clients parse. The Codex backend frequently
     * answers `{"detail": "..."}` instead. When [overrideType] is set, e.g.
     * `insufficient_quota`, it replaces the error type and code so clients classify the
     * failure correctly.
     */
    @JvmStatic
    fun toUpstreamErrorBody(raw: String?, status: Int, overrideType: String?): String {
        var parsed: JsonNode? = null
        if (!raw.isNullOrBlank()) {
            try {
                val candidate = Json.MAPPER.readTree(raw)
                if (candidate != null && candidate.isObject) {
                    parsed = candidate
                }
            } catch (_: Exception) {
            }
        }

        if (parsed != null && overrideType == null &&
            parsed.path("error").isObject && parsed.path("error").path("message").isTextual
        ) {
            return raw.orEmpty()
        }

        val message = extractErrorMessage(parsed, raw)
        val root = Json.MAPPER.createObjectNode()
        root.set<ObjectNode>(
            "error",
            errorObject(
                message,
                overrideType ?: "upstream_error",
                overrideType ?: status.toString(),
            ),
        )
        return try {
            Json.MAPPER.writeValueAsString(root)
        } catch (_: Exception) {
            "{\"error\":{\"message\":\"Upstream error\",\"type\":\"upstream_error\"}}"
        }
    }

    @JvmStatic
    fun setSseHeaders(ctx: Context) {
        ctx.contentType(SSE_CONTENT_TYPE)
        ctx.header("Cache-Control", "no-cache, no-transform")
        ctx.header("Connection", "keep-alive")
        ctx.header("X-Accel-Buffering", "no")
    }

    private fun extractErrorMessage(parsed: JsonNode?, raw: String?): String {
        if (parsed != null) {
            val nested = parsed.path("error").path("message")
            if (nested.isTextual && nested.asText().isNotBlank()) {
                return nested.asText()
            }
            for (field in listOf("detail", "message")) {
                val value = parsed.get(field)
                if (value != null && value.isTextual && value.asText().isNotBlank()) {
                    return value.asText()
                }
            }
        }
        return raw?.takeIf { it.isNotBlank() }?.trim() ?: "Upstream error"
    }
}
