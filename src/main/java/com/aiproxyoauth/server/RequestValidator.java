package com.aiproxyoauth.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.http.Context;

public final class RequestValidator {

    private RequestValidator() {}

    public static JsonNode parseJsonObject(Context ctx, String body) throws JsonProcessingException {
        JsonNode parsed = JsonHelper.MAPPER.readTree(body);
        if (parsed == null || !parsed.isObject()) {
            JsonHelper.toErrorResponse(ctx, "Request body must be a JSON object.");
            return null;
        }
        return parsed;
    }

    public static boolean rejectMalformedJson(Context ctx, JsonProcessingException exception) {
        JsonHelper.toErrorResponse(ctx, "Malformed JSON request body.", 400, "invalid_request_error");
        return true;
    }
}
