package de.moritzf.quota.openai.proxy

import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Loopback-only reverse proxy for OpenAI-compatible clients that need an API-key shaped endpoint.
 */
class OpenAiProxyServer(
    private val port: Int,
    private val localApiKeyProvider: () -> String?,
    private val accessTokenProvider: () -> String?,
    private val accountIdProvider: () -> String?,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build(),
    private val upstreamBaseUri: URI = DEFAULT_UPSTREAM_BASE_URI,
    private val stripV1Prefix: Boolean = true,
) {
    private var server: HttpServer? = null
    private var executor: ExecutorService? = null

    val isRunning: Boolean
        get() = server != null

    fun start() {
        check(server == null) { "OpenAI proxy is already running" }

        val engine = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)
        val serverExecutor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "OpenAI Proxy Server").apply { isDaemon = true }
        }
        engine.executor = serverExecutor
        engine.createContext("/") { exchange -> handle(exchange) }
        engine.start()

        server = engine
        executor = serverExecutor
    }

    fun stop() {
        val currentServer = server
        val currentExecutor = executor
        server = null
        executor = null
        currentServer?.stop(0)
        currentExecutor?.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        try {
            addCorsHeaders(exchange.responseHeaders)
            if (exchange.requestMethod.equals("OPTIONS", ignoreCase = true)) {
                exchange.sendResponseHeaders(204, -1)
                return
            }
            if (!isLoopbackRequest(exchange)) {
                sendJsonError(exchange, 403, "OpenAI proxy only accepts loopback requests")
                return
            }
            if (!isClientAuthorized(exchange.requestHeaders)) {
                sendJsonError(exchange, 401, "Missing or invalid local proxy API key")
                return
            }

            val accessToken = accessTokenProvider()?.takeIf { it.isNotBlank() }
            if (accessToken == null) {
                sendJsonError(exchange, 503, "OpenAI login required before proxying requests")
                return
            }

            val request = buildUpstreamRequest(exchange, accessToken, accountIdProvider())
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
            sendUpstreamResponse(exchange, response)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            sendJsonError(exchange, 502, "OpenAI proxy request interrupted")
        } catch (exception: Exception) {
            sendJsonError(exchange, 502, "OpenAI proxy request failed: ${exception.message ?: exception::class.java.simpleName}")
        } finally {
            exchange.close()
        }
    }

    private fun isLoopbackRequest(exchange: HttpExchange): Boolean {
        return exchange.remoteAddress?.address?.isLoopbackAddress == true
    }

    private fun isClientAuthorized(headers: Headers): Boolean {
        val expected = localApiKeyProvider()?.takeIf { it.isNotBlank() } ?: return false
        val bearer = headers.firstValue("Authorization")
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter(' ')
            ?.trim()
        val apiKey = headers.firstValue("X-API-Key")?.trim()
        return bearer == expected || apiKey == expected
    }

    private fun buildUpstreamRequest(exchange: HttpExchange, accessToken: String, accountId: String?): HttpRequest {
        val builder = HttpRequest.newBuilder(targetUri(exchange.requestURI))
        copyRequestHeaders(exchange.requestHeaders, builder)
        builder.header("Authorization", "Bearer $accessToken")
        if (!accountId.isNullOrBlank()) {
            builder.header("ChatGPT-Account-Id", accountId.trim())
        }
        if (!exchange.requestHeaders.hasHeader("OpenAI-Beta")) {
            builder.header("OpenAI-Beta", "responses=experimental")
        }
        if (!exchange.requestHeaders.hasHeader("originator")) {
            builder.header("originator", "openai_usage_quota_intellij")
        }
        if (!exchange.requestHeaders.hasHeader("User-Agent")) {
            builder.header("User-Agent", "openai-usage-quota-intellij")
        }

        builder.method(exchange.requestMethod, bodyPublisher(exchange))
        return builder.build()
    }

    private fun bodyPublisher(exchange: HttpExchange): HttpRequest.BodyPublisher {
        val method = exchange.requestMethod.uppercase(Locale.US)
        val contentLength = exchange.requestHeaders.firstValue("Content-Length")?.toLongOrNull() ?: 0L
        val hasBody = method in METHODS_WITH_REQUEST_BODY ||
            contentLength > 0L ||
            exchange.requestHeaders.hasHeader("Transfer-Encoding")
        return if (hasBody) {
            HttpRequest.BodyPublishers.ofInputStream { exchange.requestBody }
        } else {
            HttpRequest.BodyPublishers.noBody()
        }
    }

    private fun copyRequestHeaders(headers: Headers, builder: HttpRequest.Builder) {
        headers.forEach { (name, values) ->
            if (name.isNullOrBlank() || name.isBlockedRequestHeader()) {
                return@forEach
            }
            values.forEach { value ->
                builder.header(name, value)
            }
        }
    }

    private fun sendUpstreamResponse(exchange: HttpExchange, response: HttpResponse<InputStream>) {
        response.headers().map().forEach { (name, values) ->
            if (name.isBlank() || name.isBlockedResponseHeader()) {
                return@forEach
            }
            values.forEach { value ->
                exchange.responseHeaders.add(name, value)
            }
        }

        val noBody = exchange.requestMethod.equals("HEAD", ignoreCase = true) || response.statusCode() in NO_BODY_STATUS_CODES
        exchange.sendResponseHeaders(response.statusCode(), if (noBody) -1 else 0)
        if (!noBody) {
            response.body().use { input ->
                exchange.responseBody.use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        output.flush()
                    }
                }
            }
        } else {
            response.body().close()
        }
    }

    private fun sendJsonError(exchange: HttpExchange, status: Int, message: String) {
        if (exchange.responseCode != -1) return
        addCorsHeaders(exchange.responseHeaders)
        val body = "{\"error\":{\"message\":${message.toJsonString()},\"type\":\"openai_proxy_error\"}}"
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { output -> output.write(bytes) }
    }

    private fun targetUri(requestUri: URI): URI {
        val originalPath = requestUri.rawPath.takeUnless { it.isNullOrBlank() } ?: "/"
        val mappedPath = if (stripV1Prefix && (originalPath == "/v1" || originalPath.startsWith("/v1/"))) {
            originalPath.removePrefix("/v1").ifBlank { "/" }
        } else {
            originalPath
        }
        val base = upstreamBaseUri.toString().trimEnd('/')
        val query = requestUri.rawQuery?.let { "?$it" }.orEmpty()
        return URI.create("$base$mappedPath$query")
    }

    private fun addCorsHeaders(headers: Headers) {
        headers.set("Access-Control-Allow-Origin", "*")
        headers.set("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS")
        headers.set("Access-Control-Allow-Headers", "Authorization, X-API-Key, Content-Type, Accept, OpenAI-Beta")
    }

    private fun Headers.firstValue(name: String): String? = this[name]?.firstOrNull()

    private fun Headers.hasHeader(name: String): Boolean = this[name]?.isNotEmpty() == true

    private fun String.isBlockedRequestHeader(): Boolean = lowercase(Locale.US) in BLOCKED_REQUEST_HEADERS

    private fun String.isBlockedResponseHeader(): Boolean = lowercase(Locale.US) in BLOCKED_RESPONSE_HEADERS

    private fun String.toJsonString(): String {
        val escaped = buildString {
            this@toJsonString.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
        }
        return "\"$escaped\""
    }

    companion object {
        @JvmField
        val DEFAULT_UPSTREAM_BASE_URI: URI = URI.create("https://chatgpt.com/backend-api/codex")

        private val METHODS_WITH_REQUEST_BODY = setOf("POST", "PUT", "PATCH", "DELETE")
        private const val DEFAULT_BUFFER_SIZE = 8192
        private val NO_BODY_STATUS_CODES = setOf(204, 205, 304)
        private val BLOCKED_REQUEST_HEADERS = setOf(
            "authorization",
            "connection",
            "content-length",
            "cookie",
            "expect",
            "host",
            "proxy-authorization",
            "transfer-encoding",
            "upgrade",
            "x-api-key",
        )
        private val BLOCKED_RESPONSE_HEADERS = setOf(
            "connection",
            "content-length",
            "transfer-encoding",
            "upgrade",
        )
    }
}
