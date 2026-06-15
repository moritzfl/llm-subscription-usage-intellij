package de.moritzf.proxy.server
import de.moritzf.proxy.logging.RequestLogger
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import io.javalin.http.Context
object RequestValidator {
    fun parseLoggedJsonObject(ctx: Context, requestLogger: RequestLogger, requestId: String): JsonNode? {
        val body = ctx.body()
        requestLogger.logInbound(requestId, ctx, body)
        return try {
            parseJsonObject(ctx, body)
        } catch (_: JsonProcessingException) {
            rejectMalformedJson(ctx)
            null
        }
    }
    fun parseJsonObject(ctx: Context, body: String): JsonNode? {
        val parsed = JsonHelper.MAPPER.readTree(body)
        if (parsed == null || !parsed.isObject) {
            JsonHelper.toErrorResponse(ctx, "Request body must be a JSON object.")
            return null
        }
        return parsed
    }
    fun rejectMalformedJson(ctx: Context) {
        JsonHelper.toErrorResponse(ctx, "Malformed JSON request body.", 400, "invalid_request_error")
    }
}
