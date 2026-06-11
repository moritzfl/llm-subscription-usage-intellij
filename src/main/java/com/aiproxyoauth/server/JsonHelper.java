package com.aiproxyoauth.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aiproxyoauth.util.Json;
import io.javalin.http.Context;

import java.nio.charset.StandardCharsets;

public final class JsonHelper {

    /** Shared mapper — alias to {@link Json#MAPPER}. */
    public static final ObjectMapper MAPPER = Json.MAPPER;

    public static final String SSE_CONTENT_TYPE = "text/event-stream; charset=utf-8";
    public static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";

    private JsonHelper() {}

    public static void toJsonResponse(Context ctx, Object body) {
        toJsonResponse(ctx, body, 200);
    }

    public static void toJsonResponse(Context ctx, Object body, int status) {
        ctx.status(status);
        ctx.contentType(JSON_CONTENT_TYPE);
        try {
            String json = Json.MAPPER.writeValueAsString(body);
            AccessLogFields.responseBytes(ctx, json.getBytes(StandardCharsets.UTF_8).length);
            ctx.result(json);
        } catch (Exception e) {
            String fallback = "{}";
            AccessLogFields.responseBytes(ctx, fallback.getBytes(StandardCharsets.UTF_8).length);
            ctx.result(fallback);
        }
    }

    public static void toErrorResponse(Context ctx, String message) {
        toErrorResponse(ctx, message, 400, "invalid_request_error");
    }

    public static void toErrorResponse(Context ctx, String message, int status, String type) {
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.set("error", errorObject(message, type, String.valueOf(status)));
        toJsonResponse(ctx, root, status);
    }

    /** Builds an OpenAI-shaped error object: {@code {message, type, param, code}}. */
    public static ObjectNode errorObject(String message, String type, String code) {
        ObjectNode error = Json.MAPPER.createObjectNode();
        error.put("message", message);
        error.put("type", type);
        error.putNull("param");
        error.put("code", code);
        return error;
    }

    public static String mapFinishReason(String finishReason) {
        if (finishReason == null) return null;
        return switch (finishReason) {
            case "stop" -> "stop";
            case "length", "max_output_tokens" -> "length";
            case "tool-calls", "tool_calls" -> "tool_calls";
            case "content-filter", "content_filter" -> "content_filter";
            default -> null;
        };
    }

    public static ObjectNode toUsage(JsonNode usageNode) {
        ObjectNode usage = Json.MAPPER.createObjectNode();
        if (usageNode == null || !usageNode.isObject()) {
            usage.put("prompt_tokens", 0);
            usage.put("completion_tokens", 0);
            usage.put("total_tokens", 0);
            return usage;
        }
        usage.put("prompt_tokens", usageNode.path("input_tokens").asInt(0));
        usage.put("completion_tokens", usageNode.path("output_tokens").asInt(0));
        usage.put("total_tokens",
                usageNode.path("input_tokens").asInt(0) + usageNode.path("output_tokens").asInt(0));

        int cachedTokens = usageNode.path("input_tokens_details").path("cached_tokens").asInt(-1);
        if (cachedTokens >= 0) {
            ObjectNode promptDetails = Json.MAPPER.createObjectNode();
            promptDetails.put("cached_tokens", cachedTokens);
            usage.set("prompt_tokens_details", promptDetails);
        }

        int reasoningTokens = usageNode.path("output_tokens_details").path("reasoning_tokens").asInt(-1);
        if (reasoningTokens >= 0) {
            ObjectNode completionDetails = Json.MAPPER.createObjectNode();
            completionDetails.put("reasoning_tokens", reasoningTokens);
            usage.set("completion_tokens_details", completionDetails);
        }

        return usage;
    }

    public static String toUpstreamErrorBody(String raw, int status) {
        return toUpstreamErrorBody(raw, status, null);
    }

    /**
     * Normalizes any upstream error payload to the OpenAI {@code {"error":{...}}}
     * envelope, which is the only shape OpenAI-compatible clients parse. The Codex
     * backend frequently answers {@code {"detail": "..."}} instead. When
     * {@code overrideType} is set (e.g. "insufficient_quota"), it replaces the error
     * type and code so clients classify the failure correctly.
     */
    public static String toUpstreamErrorBody(String raw, int status, String overrideType) {
        JsonNode parsed = null;
        if (raw != null && !raw.isBlank()) {
            try {
                JsonNode candidate = Json.MAPPER.readTree(raw);
                if (candidate != null && candidate.isObject()) {
                    parsed = candidate;
                }
            } catch (Exception ignored) {}
        }

        if (parsed != null && overrideType == null
                && parsed.path("error").isObject()
                && parsed.path("error").path("message").isTextual()) {
            return raw; // already a usable OpenAI error envelope
        }

        String message = extractErrorMessage(parsed, raw);
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.set("error", errorObject(
                message,
                overrideType != null ? overrideType : "upstream_error",
                overrideType != null ? overrideType : String.valueOf(status)));
        try {
            return Json.MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"error\":{\"message\":\"Upstream error\",\"type\":\"upstream_error\"}}";
        }
    }

    private static String extractErrorMessage(JsonNode parsed, String raw) {
        if (parsed != null) {
            JsonNode nested = parsed.path("error").path("message");
            if (nested.isTextual() && !nested.asText().isBlank()) {
                return nested.asText();
            }
            for (String field : new String[]{"detail", "message"}) {
                JsonNode value = parsed.get(field);
                if (value != null && value.isTextual() && !value.asText().isBlank()) {
                    return value.asText();
                }
            }
        }
        return raw != null && !raw.isBlank() ? raw.strip() : "Upstream error";
    }

    public static void setSseHeaders(Context ctx) {
        ctx.contentType(SSE_CONTENT_TYPE);
        ctx.header("Cache-Control", "no-cache, no-transform");
        ctx.header("Connection", "keep-alive");
        ctx.header("X-Accel-Buffering", "no");
    }
}
