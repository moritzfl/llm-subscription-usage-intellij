package com.aiproxyoauth.transport

import com.aiproxyoauth.auth.CredentialsProvider
import com.aiproxyoauth.config.ServerConfig
import com.aiproxyoauth.logging.RequestLogger
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.Executors
import javax.net.ssl.SSLSession

open class CodexHttpClient {
    private val httpClient: HttpClient
    private val credentialsProvider: CredentialsProvider
    private val baseUrl: String
    private val requestLogger: RequestLogger

    constructor(config: ServerConfig, credentialsProvider: CredentialsProvider) : this(
        config,
        HttpClient.newBuilder()
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build(),
        credentialsProvider,
    )

    constructor(config: ServerConfig, httpClient: HttpClient, credentialsProvider: CredentialsProvider) {
        this.credentialsProvider = credentialsProvider
        baseUrl = config.baseUrl()
        this.httpClient = httpClient
        requestLogger = RequestLogger(config.fullRequestLogging(), Path.of(config.requestLogDir()))
    }

    open fun getHttpClient(): HttpClient = httpClient

    @Throws(Exception::class)
    open fun request(
        path: String,
        method: String?,
        body: String?,
        extraHeaders: Map<String, String>?,
    ): HttpResponse<InputStream> {
        return request(path, method, body, extraHeaders, null, null)
    }

    @Throws(Exception::class)
    open fun request(
        path: String,
        method: String?,
        body: String?,
        extraHeaders: Map<String, String>?,
        requestId: String?,
        promptCacheKey: String?,
    ): HttpResponse<InputStream> {
        val logRequestId = requestId ?: requestLogger.nextRequestId()
        val authHeaders = credentialsProvider.getAuthHeaders()
        var response = httpClient.send(
            buildRequest(path, method, body, extraHeaders, promptCacheKey, logRequestId, authHeaders),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        if (response.statusCode() == 401 && refreshAfterUnauthorized(authHeaders)) {
            // Stream body of the rejected response is unused; discard it before retrying.
            drainQuietly(response)
            response = httpClient.send(
                buildRequest(
                    path,
                    method,
                    body,
                    extraHeaders,
                    promptCacheKey,
                    logRequestId,
                    credentialsProvider.getAuthHeaders(),
                ),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
        }
        return withLoggedStreamingBody(response, logRequestId)
    }

    /**
     * With full request logging enabled, tees the streaming body so the bytes that
     * actually flowed are logged once the stream is consumed or closed. Without it,
     * the entry is written immediately with a placeholder (a no-op when disabled).
     */
    private fun withLoggedStreamingBody(
        response: HttpResponse<InputStream>,
        requestId: String,
    ): HttpResponse<InputStream> {
        if (!requestLogger.isEnabled()) {
            requestLogger.logUpstreamResponse(
                requestId,
                response.statusCode(),
                responseHeaders(response),
                "[streaming body omitted]",
            )
            return response
        }
        val tee = LoggingTeeInputStream(response.body()) { captured ->
            requestLogger.logUpstreamResponse(requestId, response.statusCode(), responseHeaders(response), captured)
        }
        return BodyReplacingHttpResponse(response, tee)
    }

    @Throws(Exception::class)
    open fun requestString(
        path: String,
        method: String?,
        body: String?,
        extraHeaders: Map<String, String>?,
    ): HttpResponse<String> {
        val requestId = requestLogger.nextRequestId()
        val authHeaders = credentialsProvider.getAuthHeaders()
        var response = httpClient.send(
            buildRequest(path, method, body, extraHeaders, null, requestId, authHeaders),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (response.statusCode() == 401 && refreshAfterUnauthorized(authHeaders)) {
            response = httpClient.send(
                buildRequest(path, method, body, extraHeaders, null, requestId, credentialsProvider.getAuthHeaders()),
                HttpResponse.BodyHandlers.ofString(),
            )
        }
        requestLogger.logUpstreamResponse(requestId, response.statusCode(), responseHeaders(response), response.body())
        return response
    }

    /**
     * Asks the credentials provider to refresh after an upstream 401, passing the exact
     * Authorization header the rejected request carried so the provider can deduplicate
     * concurrent refreshes. The refresh itself is owned by the provider (the IDE plugin
     * routes it to its secure-storage auth service); the proxy only triggers it. Returns
     * false when nothing was refreshed so the caller surfaces the original 401 instead of
     * retrying with the same doomed token.
     */
    private fun refreshAfterUnauthorized(sentAuthHeaders: Map<String, String>): Boolean {
        return try {
            credentialsProvider.refreshAfterUnauthorized(sentAuthHeaders["Authorization"])
        } catch (_: Exception) {
            false
        }
    }

    @Throws(Exception::class)
    private fun buildRequest(
        path: String,
        method: String?,
        body: String?,
        extraHeaders: Map<String, String>?,
        promptCacheKey: String?,
        requestId: String,
        authHeaders: Map<String, String>,
    ): HttpRequest {
        val targetUrl = UrlResolver.resolveTargetUrl(path, baseUrl)
        val loggedHeaders = LinkedHashMap<String, String>()

        val builder = HttpRequest.newBuilder()
            .uri(URI.create(targetUrl))

        authHeaders.forEach { (name, value) ->
            builder.header(name, value)
            loggedHeaders[name] = value
        }
        extraHeaders?.forEach { (name, value) ->
            builder.header(name, value)
            loggedHeaders[name] = value
        }
        if (!promptCacheKey.isNullOrBlank()) {
            builder.header(CONVERSATION_ID_HEADER, promptCacheKey)
            builder.header(SESSION_ID_HEADER, promptCacheKey)
            loggedHeaders[CONVERSATION_ID_HEADER] = promptCacheKey
            loggedHeaders[SESSION_ID_HEADER] = promptCacheKey
        }

        if (!body.isNullOrEmpty()) {
            builder.method(method ?: "POST", HttpRequest.BodyPublishers.ofString(body))
        } else {
            builder.method(method ?: "GET", HttpRequest.BodyPublishers.noBody())
        }

        requestLogger.logUpstreamRequest(requestId, method ?: "GET", path, loggedHeaders, body)
        return builder.build()
    }

    /**
     * Copies everything read through it into a bounded buffer and hands the captured
     * text to [onComplete] exactly once, at EOF or close, whichever comes first.
     */
    private class LoggingTeeInputStream(
        delegate: InputStream,
        private val onComplete: (String) -> Unit,
    ) : FilterInputStream(delegate) {
        private val captured = ByteArrayOutputStream()
        private var truncated = false
        private var completed = false

        @Throws(IOException::class)
        override fun read(): Int {
            val byte = super.read()
            if (byte == -1) {
                complete()
            } else {
                capture(byteArrayOf(byte.toByte()), 0, 1)
            }
            return byte
        }

        @Throws(IOException::class)
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = super.read(buffer, offset, length)
            if (count == -1) {
                complete()
            } else {
                capture(buffer, offset, count)
            }
            return count
        }

        @Throws(IOException::class)
        override fun close() {
            complete()
            super.close()
        }

        private fun capture(buffer: ByteArray, offset: Int, count: Int) {
            val room = MAX_CAPTURE_BYTES - captured.size()
            if (room <= 0) {
                truncated = true
                return
            }
            val toWrite = minOf(room, count)
            captured.write(buffer, offset, toWrite)
            if (toWrite < count) {
                truncated = true
            }
        }

        private fun complete() {
            if (completed) {
                return
            }
            completed = true
            val body = captured.toString(StandardCharsets.UTF_8)
            onComplete(if (truncated) "$body\n[capture truncated]" else body)
        }

        companion object {
            private const val MAX_CAPTURE_BYTES = 256 * 1024
        }
    }

    /** Delegates everything to the original response except the (tee-wrapped) body. */
    private class BodyReplacingHttpResponse(
        private val delegate: HttpResponse<InputStream>,
        private val replacementBody: InputStream,
    ) : HttpResponse<InputStream> {
        override fun statusCode(): Int = delegate.statusCode()

        override fun request(): HttpRequest = delegate.request()

        override fun previousResponse(): Optional<HttpResponse<InputStream>> = delegate.previousResponse()

        override fun headers(): HttpHeaders = delegate.headers()

        override fun body(): InputStream = replacementBody

        override fun sslSession(): Optional<SSLSession> = delegate.sslSession()

        override fun uri(): URI = delegate.uri()

        override fun version(): HttpClient.Version = delegate.version()
    }

    companion object {
        private val CONVERSATION_ID_HEADER = codexIdHeader("conversation")
        private val SESSION_ID_HEADER = codexIdHeader("session")

        private fun codexIdHeader(prefix: String): String = prefix + "_id"

        private fun drainQuietly(response: HttpResponse<InputStream>) {
            try {
                response.body()?.use { it.readAllBytes() }
            } catch (_: Exception) {
            }
        }

        private fun <T> responseHeaders(response: HttpResponse<T>): Map<String, List<String>> {
            return response.headers()?.map() ?: emptyMap()
        }
    }
}
