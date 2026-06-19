package de.moritzf.proxy.server
import de.moritzf.proxy.logging.RequestLogger
import io.javalin.http.Context
import kotlinx.serialization.json.JsonObject
object RequestValidator {
    fun parseLoggedJsonObject(ctx: Context, requestLogger: RequestLogger, requestId: String): JsonObject? {
        val body = ctx.body()
        requestLogger.logInbound(requestId, ctx, body)
        return try {
            parseJsonObject(ctx, body)
        } catch (_: Exception) {
            rejectMalformedJson(ctx)
            null
        }
    }
    fun parseJsonObject(ctx: Context, body: String): JsonObject? {
        val parsed = JsonHelper.parseToJsonElement(body)
        if (parsed !is JsonObject) {
            JsonHelper.toErrorResponse(ctx, "Request body must be a JSON object.")
            return null
        }
        return parsed
    }
    fun rejectMalformedJson(ctx: Context) {
        JsonHelper.toErrorResponse(ctx, "Malformed JSON request body.", 400, "invalid_request_error")
    }
}
