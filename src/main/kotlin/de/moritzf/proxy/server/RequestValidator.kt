package de.moritzf.proxy.server
import de.moritzf.proxy.logging.RequestLogger
import io.ktor.server.request.receiveText
import kotlinx.serialization.json.JsonObject
object RequestValidator {
    suspend fun parseLoggedJsonObject(ctx: ProxyCall, requestLogger: RequestLogger, requestId: String): JsonObject? {
        val body = ctx.call.receiveText()
        requestLogger.logInbound(requestId, ctx, body)
        return try {
            parseJsonObject(ctx, body)
        } catch (_: Exception) {
            rejectMalformedJson(ctx)
            null
        }
    }
    suspend fun parseJsonObject(ctx: ProxyCall, body: String): JsonObject? {
        val parsed = JsonHelper.parseToJsonElement(body)
        if (parsed !is JsonObject) {
            JsonHelper.toErrorResponse(ctx, "Request body must be a JSON object.")
            return null
        }
        return parsed
    }
    suspend fun rejectMalformedJson(ctx: ProxyCall) {
        JsonHelper.toErrorResponse(ctx, "Malformed JSON request body.", 400, "invalid_request_error")
    }
}
