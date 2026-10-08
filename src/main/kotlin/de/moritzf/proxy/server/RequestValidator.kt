package de.moritzf.proxy.server

import de.moritzf.proxy.logging.RequestLogger
import io.ktor.http.BadContentTypeFormatException
import io.ktor.http.HttpHeaders
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.contentCharset
import io.ktor.server.request.receive
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.core.readText
import io.ktor.utils.io.readBuffer
import kotlinx.serialization.json.JsonObject

object RequestValidator {
    suspend fun parseLoggedJsonObject(
        ctx: ProxyCall,
        requestLogger: RequestLogger,
        requestId: String,
    ): JsonObject? {
        val charset =
            try {
                ctx.call.request.contentCharset() ?: Charsets.UTF_8
            } catch (cause: BadContentTypeFormatException) {
                throw BadRequestException(
                    "Illegal Content-Type format: ${ctx.header(HttpHeaders.ContentType)}",
                    cause,
                )
            }
        // The KClass overload stays in Ktor instead of inlining its old receiveNullable API.
        val body =
            ctx.call.receive(ByteReadChannel::class).readBuffer().use { it.readText(charset) }
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
        JsonHelper.toErrorResponse(
            ctx,
            "Malformed JSON request body.",
            400,
            "invalid_request_error",
        )
    }
}
