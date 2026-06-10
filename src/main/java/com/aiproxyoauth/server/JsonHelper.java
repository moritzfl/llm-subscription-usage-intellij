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
        ObjectNode error = Json.MAPPER.createObjectNode();
        error.put("message", message);
        error.put("type", type);
        root.set("error", error);
        toJsonResponse(ctx, root, status);
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
        if (raw != null && !raw.isBlank()) {
            try {
                JsonNode parsed = Json.MAPPER.readTree(raw);
                if (parsed != null && parsed.isObject()) {
                    return raw; // already valid JSON object
                }
            } catch (Exception ignored) {}
        }
        ObjectNode root = Json.MAPPER.createObjectNode();
        ObjectNode error = Json.MAPPER.createObjectNode();
        String msg = (raw != null && !raw.isBlank()) ? raw.strip() : "Upstream error";
        error.put("message", msg);
        error.put("type", "upstream_error");
        error.put("code", String.valueOf(status));
        root.set("error", error);
        try {
            return Json.MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"error\":{\"message\":\"Upstream error\",\"type\":\"upstream_error\"}}";
        }
    }

    public static void setSseHeaders(Context ctx) {
        ctx.contentType(SSE_CONTENT_TYPE);
        ctx.header("Cache-Control", "no-cache, no-transform");
        ctx.header("Connection", "keep-alive");
        ctx.header("X-Accel-Buffering", "no");
    }
}
