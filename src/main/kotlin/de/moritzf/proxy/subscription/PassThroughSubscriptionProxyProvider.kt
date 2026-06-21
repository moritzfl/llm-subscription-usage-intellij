package de.moritzf.proxy.subscription

import de.moritzf.proxy.logging.RequestLogger
import de.moritzf.proxy.server.AccessLogFields
import de.moritzf.proxy.server.JsonHelper
import de.moritzf.proxy.server.ProxyCall
import de.moritzf.proxy.server.UpstreamErrorMapper
import de.moritzf.proxy.server.createObjectNode
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondOutputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class PassThroughSubscriptionProxyProvider(
    override val id: String,
    override val displayName: String,
    private val litellmProvider: String,
    private val baseUri: URI,
    private val accessTokenProvider: () -> String?,
    private val tokenRefresher: (staleAccessToken: String?) -> String? = { null },
    private val modelMappingsProvider: () -> List<ModelMapping>,
    private val defaultHeaders: Map<String, String> = emptyMap(),
    private val forwardedRequestHeadersTransformer: (SubscriptionProxyRequest, Map<String, String>) -> Map<String, String> = { _, headers -> headers },
    private val requestHeadersProvider: (SubscriptionProxyRequest) -> Map<String, String> = { emptyMap() },
    private val requestBodyTransformer: (SubscriptionProxyRequest, JsonObject) -> JsonObject = { _, body -> body },
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build(),
    private val requestLogger: RequestLogger,
) : SubscriptionProxyProvider {
    private val upstreamErrorMapper = UpstreamErrorMapper()

    override fun isConfigured(): Boolean = accessTokenProvider().trimmedOrNull() != null

    override fun models(): List<SubscriptionProxyModel> {
        if (!isConfigured()) return emptyList()
        return modelMappingsProvider().map { mapping ->
            SubscriptionProxyModel(
                localId = mapping.localId,
                upstreamId = mapping.upstreamId,
                providerId = id,
                providerName = displayName,
                litellmProvider = litellmProvider,
                supportedRoutes = mapping.supportedRoutes,
                supportsFunctionCalling = mapping.supportsFunctionCalling,
                supportsParallelFunctionCalling = mapping.supportsParallelFunctionCalling,
                supportsToolChoice = mapping.supportsToolChoice,
                supportsVision = mapping.supportsVision,
                supportsPromptCaching = mapping.supportsPromptCaching,
                maxInputTokens = mapping.maxInputTokens,
                maxOutputTokens = mapping.maxOutputTokens,
                isDefault = mapping.isDefault,
            )
        }
    }

    override suspend fun handle(ctx: ProxyCall, request: SubscriptionProxyRequest) {
        val token = accessTokenProvider().trimmedOrNull()
        if (token == null) {
            JsonHelper.toErrorResponse(ctx, "$displayName login required.", 401, "authentication_error")
            return
        }
        val upstream = withContext(Dispatchers.IO) { sendWithRefresh(ctx, request, token) }
        AccessLogFields.upstreamStatus(ctx, upstream.statusCode())
        if (upstream.statusCode() !in 200..<300) {
            upstreamErrorMapper.writeResponse(ctx, requestLogger, request.requestId, upstream)
            return
        }
        copyResponse(ctx, upstream)
    }

    private fun sendWithRefresh(
        ctx: ProxyCall,
        request: SubscriptionProxyRequest,
        token: String,
    ): HttpResponse<InputStream> {
        var response = httpClient.send(
            buildRequest(ctx, request, token),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        if (response.statusCode() == 401) {
            val refreshed = refreshAfterUnauthorized(token)
            if (refreshed != null) {
                drainQuietly(response)
                response = httpClient.send(
                    buildRequest(ctx, request, refreshed),
                    HttpResponse.BodyHandlers.ofInputStream(),
                )
            }
        }
        return response
    }

    private fun buildRequest(
        ctx: ProxyCall,
        request: SubscriptionProxyRequest,
        accessToken: String,
    ): HttpRequest {
        val upstreamBody = requestBodyTransformer(request, rewriteModel(request.body, request.model.upstreamId))
        val payload = JsonHelper.encodeToString(upstreamBody)
        val upstreamPath = request.route.upstreamPath
        val targetUrl = resolveUpstreamUrl(upstreamPath)
        val loggedHeaders = LinkedHashMap<String, String>()
        val builder = HttpRequest.newBuilder(URI.create(targetUrl))
            .header(HttpHeaders.Authorization, "Bearer $accessToken")
        loggedHeaders[HttpHeaders.Authorization] = "Bearer $accessToken"
        forwardedRequestHeadersTransformer(request, forwardedRequestHeaders(ctx)).forEach { (name, value) ->
            builder.header(name, value)
            loggedHeaders[name] = value
        }
        defaultHeaders.forEach { (name, value) ->
            builder.setHeader(name, value)
            loggedHeaders[name] = value
        }
        requestHeadersProvider(request).forEach { (name, value) ->
            builder.setHeader(name, value)
            loggedHeaders[name] = value
        }
        if (HttpHeaders.ContentType !in loggedHeaders.keys) {
            builder.header(HttpHeaders.ContentType, JsonHelper.JSON_CONTENT_TYPE)
            loggedHeaders[HttpHeaders.ContentType] = JsonHelper.JSON_CONTENT_TYPE
        }
        builder.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
        requestLogger.logUpstreamRequest(request.requestId, "POST", upstreamPath, loggedHeaders, payload)
        return builder.build()
    }

    private fun refreshAfterUnauthorized(staleToken: String): String? {
        return try {
            tokenRefresher(staleToken).trimmedOrNull()?.takeIf { it != staleToken }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun copyResponse(ctx: ProxyCall, upstream: HttpResponse<InputStream>) {
        copySelectedResponseHeaders(ctx, upstream)
        ctx.setStatus(upstream.statusCode())
        ctx.call.respondOutputStream(responseContentType(upstream), HttpStatusCode.fromValue(upstream.statusCode())) {
            upstream.body().use { stream ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = stream.read(buffer)
                    if (read == -1) break
                    write(buffer, 0, read)
                    AccessLogFields.addResponseBytes(ctx, read.toLong())
                    flush()
                }
            }
        }
        ctx.handled = true
    }

    private fun resolveUpstreamUrl(path: String): String {
        return baseUri.toString().trimEnd('/') + "/" + path.trimStart('/')
    }

    data class ModelMapping(
        val localId: String,
        val upstreamId: String,
        val supportedRoutes: Set<SubscriptionProxyRoute>,
        val supportsFunctionCalling: Boolean = true,
        val supportsParallelFunctionCalling: Boolean = false,
        val supportsToolChoice: Boolean = true,
        val supportsVision: Boolean = true,
        val supportsPromptCaching: Boolean = false,
        val maxInputTokens: Int? = null,
        val maxOutputTokens: Int? = null,
        val isDefault: Boolean = false,
    )

    companion object {
        private val FORWARDED_REQUEST_HEADERS = listOf(
            HttpHeaders.Accept,
            HttpHeaders.ContentType,
            "OpenAI-Beta",
            "anthropic-version",
            "anthropic-beta",
        )
        private val HOP_BY_HOP_RESPONSE_HEADERS = setOf(
            HttpHeaders.Connection.lowercase(Locale.ROOT),
            HttpHeaders.ContentLength.lowercase(Locale.ROOT),
            HttpHeaders.ContentType.lowercase(Locale.ROOT),
            HttpHeaders.TransferEncoding.lowercase(Locale.ROOT),
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "upgrade",
        )

        private fun rewriteModel(body: JsonObject, upstreamModel: String): JsonObject {
            return buildJsonObject {
                body.forEach { (key, value) ->
                    put(key, if (key == "model") JsonPrimitive(upstreamModel) else value)
                }
                if ("model" !in body) {
                    put("model", upstreamModel)
                }
            }
        }

        private fun forwardedRequestHeaders(ctx: ProxyCall): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            FORWARDED_REQUEST_HEADERS.forEach { name ->
                ctx.header(name)?.takeIf { it.isNotBlank() }?.let { result[name] = it }
            }
            return result
        }

        private fun <T> responseContentType(response: HttpResponse<T>): ContentType {
            val raw = response.headers().firstValue(HttpHeaders.ContentType).orElse(JsonHelper.JSON_CONTENT_TYPE)
            return runCatching { ContentType.parse(raw) }.getOrElse { ContentType.Application.Json }
        }

        private fun <T> copySelectedResponseHeaders(ctx: ProxyCall, response: HttpResponse<T>) {
            response.headers().map().forEach { (name, values) ->
                if (shouldForwardResponseHeader(name)) {
                    values.forEach { value -> ctx.responseHeader(name, value) }
                }
            }
        }

        internal fun shouldForwardResponseHeader(name: String): Boolean {
            val normalized = name.lowercase(Locale.ROOT)
            return !normalized.startsWith(':') && normalized !in HOP_BY_HOP_RESPONSE_HEADERS
        }

        private fun drainQuietly(response: HttpResponse<InputStream>) {
            try {
                response.body()?.use { it.readAllBytes() }
            } catch (_: Exception) {
            }
        }

        private fun String?.trimmedOrNull(): String? = this?.trim()?.takeIf { it.isNotBlank() }
    }
}
