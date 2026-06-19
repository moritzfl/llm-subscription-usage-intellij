package de.moritzf.proxy.server
import de.moritzf.proxy.logging.RequestLogger
import de.moritzf.proxy.transport.CodexHttpClient
import io.javalin.http.Context
import io.javalin.http.Handler
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
class CodexJsonHandler(
    private val client: CodexHttpClient,
    private val requestLogger: RequestLogger,
    private val upstreamPath: String,
) : Handler {
    private val upstreamErrorMapper = UpstreamErrorMapper()
    override fun handle(ctx: Context) {
        val requestId = requestId(ctx)
        val body = RequestValidator.parseLoggedJsonObject(ctx, requestLogger, requestId) ?: return
        AccessLogFields.mode(ctx, "sync")
        val upstream = UpstreamRetry.withRetries(ctx.header("x-litellm-num-retries")) {
            client.request(
                upstreamPath,
                "POST",
                JsonHelper.encodeToString(body),
                mapOf("Content-Type" to "application/json"),
                requestId,
                null,
            )
        }
        AccessLogFields.upstreamStatus(ctx, upstream.statusCode())
        if (upstream.statusCode() !in 200..<300) {
            upstreamErrorMapper.writeResponse(ctx, requestLogger, requestId, upstream)
            return
        }
        upstream.body().use { responseStream ->
            val rawBody = JsonHelper.readUtf8Body(responseStream)
            ctx.status(upstream.statusCode())
            ctx.contentType(responseContentType(upstream))
            copySelectedResponseHeaders(ctx, upstream)
            AccessLogFields.responseBytes(ctx, rawBody.toByteArray(StandardCharsets.UTF_8).size.toLong())
            ctx.result(rawBody)
        }
    }
    private fun requestId(ctx: Context): String {
        var requestId = ctx.attribute<String>(AccessLogFields.REQUEST_ID)
        if (requestId.isNullOrBlank()) {
            requestId = requestLogger.nextRequestId()
            ctx.attribute(AccessLogFields.REQUEST_ID, requestId)
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
        private fun <T> copySelectedResponseHeaders(ctx: Context, response: HttpResponse<T>) {
            FORWARDED_RESPONSE_HEADERS.forEach { header ->
                response.headers().firstValue(header).ifPresent { value -> ctx.header(header, value) }
            }
        }
    }
}
