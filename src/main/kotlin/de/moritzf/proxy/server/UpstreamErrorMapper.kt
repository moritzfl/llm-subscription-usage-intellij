package de.moritzf.proxy.server
import de.moritzf.proxy.logging.RequestLogger
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.response.respondText
import java.io.InputStream
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.Locale
class UpstreamErrorMapper {
    data class MappedUpstreamError(
        val statusCode: Int,
        val body: String,
    )
    fun map(upstreamStatus: Int, upstreamBody: String?): MappedUpstreamError {
        val quotaExhausted = isQuotaExhausted(upstreamBody)
        val rateLimited = containsMarker(upstreamBody, "rate_limit_exceeded")
        // The Codex backend reports usage/rate limits with HTTP 404; clients expect 429.
        val status = if (upstreamStatus == 404 && (quotaExhausted || rateLimited)) 429 else upstreamStatus
        // "insufficient_quota" makes OpenAI-compatible clients stop and surface the quota problem.
        val errorType = if (quotaExhausted) "insufficient_quota" else null
        return MappedUpstreamError(status, JsonHelper.toUpstreamErrorBody(upstreamBody, status, errorType))
    }
    suspend fun writeResponse(
        ctx: ProxyCall,
        requestLogger: RequestLogger,
        requestId: String,
        upstream: HttpResponse<InputStream>,
    ) {
        upstream.body().use { stream ->
            val mapped = map(upstream.statusCode(), JsonHelper.readUtf8Body(stream))
            requestLogger.logUpstreamResponse(requestId, mapped.statusCode, responseHeaders(upstream), mapped.body)
            ctx.setStatus(mapped.statusCode)
            AccessLogFields.responseBytes(ctx, mapped.body.toByteArray(StandardCharsets.UTF_8).size.toLong())
            ctx.call.respondText(
                mapped.body,
                ContentType.Application.Json.withCharset(StandardCharsets.UTF_8),
                HttpStatusCode.fromValue(mapped.statusCode),
            )
            ctx.handled = true
        }
    }
    private fun isQuotaExhausted(upstreamBody: String?): Boolean {
        return containsMarker(upstreamBody, "usage_limit_reached") ||
            containsMarker(upstreamBody, "usage_not_included") ||
            containsMarker(upstreamBody, "usage limit")
    }
    private fun containsMarker(upstreamBody: String?, marker: String): Boolean {
        return upstreamBody != null && upstreamBody.lowercase(Locale.ROOT).contains(marker)
    }
    private fun <T> responseHeaders(response: HttpResponse<T>): Map<String, List<String>> {
        return response.headers()?.map() ?: emptyMap()
    }
}
