package de.moritzf.proxy.server
import de.moritzf.proxy.logging.RequestLogger
import de.moritzf.proxy.transport.CodexHttpClient
import com.fasterxml.jackson.core.JsonProcessingException
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
        val bodyStr = ctx.body()
        requestLogger.logInbound(requestId, ctx, bodyStr)
        val body = try {
            RequestValidator.parseJsonObject(ctx, bodyStr)
        } catch (exception: JsonProcessingException) {
            RequestValidator.rejectMalformedJson(ctx, exception)
            return
        }
        if (body == null) {
            return
        }
        AccessLogFields.mode(ctx, "sync")
        val upstream = UpstreamRetry.withRetries(ctx.header("x-litellm-num-retries")) {
            client.request(
                upstreamPath,
                "POST",
                JsonHelper.MAPPER.writeValueAsString(body),
                mapOf("Content-Type" to "application/json"),
                requestId,
                null,
            )
        }
        AccessLogFields.upstreamStatus(ctx, upstream.statusCode())
        upstream.body().use { responseStream ->
            val rawBody = String(responseStream.readAllBytes(), StandardCharsets.UTF_8)
            if (upstream.statusCode() !in 200..<300) {
                val mapped = upstreamErrorMapper.map(upstream.statusCode(), rawBody)
                requestLogger.logUpstreamResponse(requestId, mapped.statusCode, responseHeaders(upstream), mapped.body)
                ctx.status(mapped.statusCode)
                ctx.contentType(JsonHelper.JSON_CONTENT_TYPE)
                AccessLogFields.responseBytes(ctx, mapped.body.toByteArray(StandardCharsets.UTF_8).size.toLong())
                ctx.result(mapped.body)
                return
            }
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
        private fun <T> responseHeaders(response: HttpResponse<T>): Map<String, List<String>> {
            return response.headers()?.map() ?: emptyMap()
        }
        private fun <T> copySelectedResponseHeaders(ctx: Context, response: HttpResponse<T>) {
            FORWARDED_RESPONSE_HEADERS.forEach { header ->
                response.headers().firstValue(header).ifPresent { value -> ctx.header(header, value) }
            }
        }
    }
}
