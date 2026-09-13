package de.moritzf.proxy.media

import de.moritzf.proxy.logging.RequestLogger
import de.moritzf.proxy.server.AccessLogFields
import de.moritzf.proxy.server.JsonHelper
import de.moritzf.proxy.server.ProxyCall
import de.moritzf.proxy.server.RequestValidator
import de.moritzf.proxy.server.stringPath
import io.ktor.http.ContentType
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respondBytes
import io.ktor.utils.io.toByteArray
import kotlinx.serialization.json.JsonObject

internal class MediaProxyHandler(
    private val operations: MediaOperations,
    private val requestLogger: RequestLogger,
) {
    suspend fun images(ctx: ProxyCall) {
        val requestId = ctx.getAttribute(AccessLogFields.REQUEST_ID) ?: requestLogger.nextRequestId()
        val body = RequestValidator.parseLoggedJsonObject(ctx, requestLogger, requestId) ?: return
        OpenAiMedia.rejectB64(OpenAiMedia.requestedImageFormat(body))?.let { message ->
            JsonHelper.toErrorResponse(ctx, message, 400, "invalid_request_error")
            return
        }
        val prompt = body.stringPath("prompt").trim()
        if (prompt.isEmpty()) {
            JsonHelper.toErrorResponse(ctx, "prompt is required.", 400, "invalid_request_error")
            return
        }
        val model = body.stringPath("model").trim()
        val providerId = OpenAiMedia.providerIdForModel(model)
        if (providerId == null) {
            JsonHelper.toErrorResponse(ctx, "Unknown media model '$model'.", 400, "invalid_request_error")
            return
        }
        try {
            val url = operations.generateImageUrl(providerId, OpenAiMedia.upstreamModel(model), prompt)
            JsonHelper.toJsonResponse(ctx, OpenAiMedia.imageUrlResponse(url))
        } catch (exception: MediaOperationException) {
            JsonHelper.toErrorResponse(ctx, exception.message ?: "Image generation failed.", 400, "invalid_request_error")
        }
    }

    suspend fun speech(ctx: ProxyCall) {
        val requestId = ctx.getAttribute(AccessLogFields.REQUEST_ID) ?: requestLogger.nextRequestId()
        val body = RequestValidator.parseLoggedJsonObject(ctx, requestLogger, requestId) ?: return
        val input = body.stringPath("input").ifBlank { body.stringPath("text") }.trim()
        if (input.isEmpty()) {
            JsonHelper.toErrorResponse(ctx, "input is required.", 400, "invalid_request_error")
            return
        }
        val model = body.stringPath("model").trim()
        val providerId = OpenAiMedia.providerIdForModel(model)
        if (providerId == null) {
            JsonHelper.toErrorResponse(ctx, "Unknown media model '$model'.", 400, "invalid_request_error")
            return
        }
        val format = body.stringPath("response_format").ifBlank { "mp3" }
        val voice = body.stringPath("voice").trim().takeIf { it.isNotEmpty() }
        try {
            val audio = operations.synthesizeSpeech(
                providerId,
                OpenAiMedia.upstreamModel(model),
                input,
                voice,
                format,
            )
            ctx.call.respondBytes(audio.bytes, ContentType.parse(audio.contentType))
        } catch (exception: MediaOperationException) {
            JsonHelper.toErrorResponse(ctx, exception.message ?: "Speech synthesis failed.", 400, "invalid_request_error")
        }
    }

    suspend fun transcriptions(ctx: ProxyCall) {
        val contentType = ctx.header("Content-Type").orEmpty()
        if (!contentType.contains("multipart/form-data", ignoreCase = true)) {
            JsonHelper.toErrorResponse(ctx, "multipart/form-data with a file field is required.", 400, "invalid_request_error")
            return
        }
        var model = ""
        var language: String? = null
        var filename = "audio.wav"
        var audio: ByteArray? = null
        ctx.call.receiveMultipart().forEachPart { part ->
            when (part) {
                is PartData.FormItem -> when (part.name) {
                    "model" -> model = part.value
                    "language" -> language = part.value
                }
                is PartData.FileItem -> {
                    filename = part.originalFileName?.ifBlank { filename } ?: filename
                    audio = part.provider().toByteArray()
                }
                else -> Unit
            }
            part.dispose()
        }
        val bytes = audio
        if (bytes == null || bytes.isEmpty()) {
            JsonHelper.toErrorResponse(ctx, "file is required.", 400, "invalid_request_error")
            return
        }
        val providerId = OpenAiMedia.providerIdForModel(model)
        if (providerId == null) {
            JsonHelper.toErrorResponse(ctx, "Unknown media model '$model'.", 400, "invalid_request_error")
            return
        }
        try {
            val json = operations.transcribe(
                providerId,
                OpenAiMedia.upstreamModel(model),
                bytes,
                filename,
                language,
            )
            JsonHelper.toJsonResponse(ctx, JsonHelper.JSON.parseToJsonElement(json))
        } catch (exception: MediaOperationException) {
            JsonHelper.toErrorResponse(ctx, exception.message ?: "Transcription failed.", 400, "invalid_request_error")
        }
    }
}
