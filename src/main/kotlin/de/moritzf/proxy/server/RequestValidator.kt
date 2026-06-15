package de.moritzf.proxy.server

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import io.javalin.http.Context

object RequestValidator {
    @JvmStatic
    @Throws(JsonProcessingException::class)
    fun parseJsonObject(ctx: Context, body: String): JsonNode? {
        val parsed = JsonHelper.MAPPER.readTree(body)
        if (parsed == null || !parsed.isObject) {
            JsonHelper.toErrorResponse(ctx, "Request body must be a JSON object.")
            return null
        }
        return parsed
    }

    @JvmStatic
    fun rejectMalformedJson(ctx: Context, exception: JsonProcessingException): Boolean {
        JsonHelper.toErrorResponse(ctx, "Malformed JSON request body.", 400, "invalid_request_error")
        return true
    }
}
