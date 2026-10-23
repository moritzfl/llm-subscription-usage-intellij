package de.moritzf.proxy.server

import de.moritzf.proxy.logging.RequestLogger
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import java.io.InputStream
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.serialization.json.JsonObject

class UpstreamErrorMapper {
    data class MappedUpstreamError(
        val statusCode: Int,
        val body: String,
    )

    fun map(upstreamStatus: Int, upstreamBody: String?): MappedUpstreamError {
        val quotaExhausted = isQuotaExhausted(upstreamBody)
        val rateLimited = containsMarker(upstreamBody, "rate_limit_exceeded")
        // The Codex backend reports usage/rate limits with HTTP 404; clients expect 429.
        val status =
            if (upstreamStatus == 404 && (quotaExhausted || rateLimited)) 429 else upstreamStatus
        // "insufficient_quota" makes OpenAI-compatible clients stop and surface the quota problem.
        val errorType = if (quotaExhausted) "insufficient_quota" else null
        return MappedUpstreamError(
            status,
            JsonHelper.toUpstreamErrorBody(upstreamBody, status, errorType),
        )
    }

    /**
     * Maps a failed/cancelled response object from a 200 SSE stream. The error object is passed
     * through unchanged unless quota handling needs to rewrite the OpenAI error type.
     */
    fun mapResponseFailure(response: JsonObject, fallbackStatus: String): MappedUpstreamError {
        val error = response.pathOrNull("error")
        val rawError =
            if (error is JsonObject) {
                JsonHelper.encodeToString(error)
            } else {
                JsonHelper.encodeToString(
                    JsonHelper.errorObject(
                        "Upstream response $fallbackStatus.",
                        "upstream_error",
                        "502",
                    )
                )
            }
        return mapStreamFailure(rawError)
    }

    /**
     * Maps an error that arrived inside an HTTP 200 SSE stream. Quota and rate-limit failures keep
     * their OpenAI error type and get a 429 so clients surface them instead of an unknown server
     * error; everything else stays a 502 upstream failure.
     */
    fun mapStreamFailure(rawError: String?): MappedUpstreamError {
        val quotaExhausted = isQuotaExhausted(rawError) || containsQuotaMarker(rawError)
        val rateLimited =
            containsMarker(rawError, "rate_limit_exceeded") ||
                containsMarker(rawError, "rate limit") ||
                containsMarker(rawError, "too many requests")
        val status = if (quotaExhausted || rateLimited) 429 else 502
        val errorType =
            when {
                quotaExhausted -> "insufficient_quota"
                rateLimited -> "rate_limit_exceeded"
                else -> null
            }
        return MappedUpstreamError(
            status,
            JsonHelper.toUpstreamErrorBody(rawError, status, errorType),
        )
    }

    suspend fun writeResponse(
        ctx: ProxyCall,
        requestLogger: RequestLogger,
        requestId: String,
        upstream: HttpResponse<InputStream>,
    ) {
        upstream.body().use { stream ->
            val mapped = map(upstream.statusCode(), JsonHelper.readUtf8Body(stream))
            requestLogger.logUpstreamResponse(
                requestId,
                mapped.statusCode,
                responseHeaders(upstream),
                mapped.body,
            )
            writeResponse(ctx, requestLogger, requestId, mapped)
        }
    }

    suspend fun writeResponse(
        ctx: ProxyCall,
        requestLogger: RequestLogger,
        requestId: String,
        mapped: MappedUpstreamError,
    ) {
        requestLogger.logClientResponse(requestId, mapped.statusCode, mapped.body)
        ctx.setStatus(mapped.statusCode)
        AccessLogFields.responseBytes(
            ctx,
            mapped.body.toByteArray(StandardCharsets.UTF_8).size.toLong(),
        )
        ctx.respondText(
            mapped.body,
            ContentType.Application.Json.withCharset(StandardCharsets.UTF_8),
            HttpStatusCode.fromValue(mapped.statusCode),
        )
        ctx.handled = true
    }

    private fun isQuotaExhausted(upstreamBody: String?): Boolean {
        return containsMarker(upstreamBody, "usage_limit_reached") ||
            containsMarker(upstreamBody, "usage_not_included") ||
            containsMarker(upstreamBody, "usage limit")
    }

    private fun containsQuotaMarker(rawError: String?): Boolean {
        return containsMarker(rawError, "insufficient_quota") ||
            containsMarker(rawError, "quota exceeded") ||
            containsMarker(rawError, "quota_exceeded") ||
            containsMarker(rawError, "quota exhausted") ||
            containsMarker(rawError, "exceeded your current quota") ||
            containsMarker(rawError, "credit balance is too low") ||
            containsMarker(rawError, "out of credits") ||
            containsMarker(rawError, "insufficient credits") ||
            containsMarker(rawError, "credit balance") ||
            containsMarker(rawError, "spending limit") ||
            containsMarker(rawError, "monthly limit")
    }

    private fun containsMarker(upstreamBody: String?, marker: String): Boolean {
        return upstreamBody != null && upstreamBody.lowercase(Locale.ROOT).contains(marker)
    }

    private fun <T> responseHeaders(response: HttpResponse<T>): Map<String, List<String>> {
        return response.headers()?.map() ?: emptyMap()
    }
}
