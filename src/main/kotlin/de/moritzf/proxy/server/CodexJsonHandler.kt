package de.moritzf.proxy.server
import de.moritzf.proxy.logging.RequestLogger
import de.moritzf.proxy.transport.CodexHttpClient
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.response.respondText
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
class CodexJsonHandler(
    private val client: CodexHttpClient,
    private val requestLogger: RequestLogger,
    private val upstreamPath: String,
) {
    private val upstreamErrorMapper = UpstreamErrorMapper()
    suspend fun handle(ctx: ProxyCall) {
        val requestId = requestId(ctx)
        val body = RequestValidator.parseLoggedJsonObject(ctx, requestLogger, requestId) ?: return
        AccessLogFields.mode(ctx, "sync")
        val upstream = withContext(Dispatchers.IO) {
            UpstreamRetry.withRetries(ctx.header("x-litellm-num-retries")) {
                client.request(
                    upstreamPath,
                    "POST",
                    JsonHelper.encodeToString(body),
                    mapOf("Content-Type" to "application/json"),
                    requestId,
                    null,
                )
            }
        }
        AccessLogFields.upstreamStatus(ctx, upstream.statusCode())
        if (upstream.statusCode() !in 200..<300) {
            upstreamErrorMapper.writeResponse(ctx, requestLogger, requestId, upstream)
            return
        }
        upstream.body().use { responseStream ->
            val rawBody = JsonHelper.readUtf8Body(responseStream)
            ctx.setStatus(upstream.statusCode())
            copySelectedResponseHeaders(ctx, upstream)
            AccessLogFields.responseBytes(ctx, rawBody.toByteArray(StandardCharsets.UTF_8).size.toLong())
            ctx.call.respondText(
                rawBody,
                ContentType.parse(responseContentType(upstream)).withCharset(StandardCharsets.UTF_8),
                HttpStatusCode.fromValue(upstream.statusCode()),
            )
            ctx.handled = true
        }
    }
    private fun requestId(ctx: ProxyCall): String {
        var requestId = ctx.getAttribute(AccessLogFields.REQUEST_ID)
        if (requestId.isNullOrBlank()) {
            requestId = requestLogger.nextRequestId()
            ctx.setAttribute(AccessLogFields.REQUEST_ID, requestId)
        }
        return requestId
    }
    companion object {
        private val FORWARDED_RESPONSE_HEADERS = listOf(
            "x-codex-turn-state",
            "x-models-etag",
            "x-reasoning-included",
            "openai-model",
        )
        private fun <T> responseContentType(response: HttpResponse<T>): String {
            val contentType = response.headers().firstValue("Content-Type")
            return if (contentType.isPresent) contentType.get() else JsonHelper.JSON_CONTENT_TYPE
        }
        private fun <T> copySelectedResponseHeaders(ctx: ProxyCall, response: HttpResponse<T>) {
            FORWARDED_RESPONSE_HEADERS.forEach { header ->
                response.headers().firstValue(header).ifPresent { value -> ctx.responseHeader(header, value) }
            }
        }
    }
}
