package de.moritzf.proxy.server

import de.moritzf.proxy.auth.AuthRequiredException
import de.moritzf.proxy.config.ServerConfig
import de.moritzf.proxy.logging.RequestLogger
import de.moritzf.proxy.model.CodexInstructionsProvider
import de.moritzf.proxy.model.ModelResolver
import de.moritzf.proxy.transport.CodexHttpClient
import de.moritzf.proxy.usage.UsageTracker
import de.moritzf.proxy.util.ApiKeyUtils
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory

class ProxyServer(
    private val config: ServerConfig,
    client: CodexHttpClient,
    modelResolver: ModelResolver,
    usageTracker: UsageTracker,
    private val apiKeyStore: ApiKeyStore,
) {
    private val app: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>
    private val requestLogger: RequestLogger

    init {
        if (config.requiresApiKeyEnforcement() && !apiKeyStore.isEnforcing()) {
            throw IllegalStateException("API key enforcement is required when binding to a non-loopback host: ${config.host}")
        }
        requestLogger = RequestLogger(config.fullRequestLogging, Path.of(config.requestLogDir))
        val instructionsProvider = if (config.codexInstructionsMode == "latest-codex") {
            CodexInstructionsProvider(
                CodexInstructionsProvider.Mode.LATEST_CODEX,
                config.instructions,
                Path.of(config.codexInstructionsCacheDir),
                Duration.ofMinutes(15),
                client.getHttpClient(),
            )
        } else {
            CodexInstructionsProvider(config.instructions)
        }
        app = embeddedServer(CIO, host = config.host, port = config.port) {
            routing {
                getProxy("/health", HealthHandler()::handle)
                getProxy("/health/liveliness", ::livenessProbe)
                getProxy("/health/liveness", ::livenessProbe)
                getProxy("/health/readiness", ::readinessProbe)
                val modelsHandler = ModelsHandler(modelResolver)
                getProxy("/v1/models", modelsHandler::handle)
                getProxy("/models", modelsHandler::handle)
                val modelInfoHandler = LiteLlmModelInfoHandler(modelResolver)
                getProxy("/v1/model/info", modelInfoHandler::handle)
                getProxy("/model/info", modelInfoHandler::handle)
                getProxy("/v1/usage", UsageHandler(usageTracker)::handle)
                val responsesHandler = ResponsesHandler(client, config, usageTracker, requestLogger, instructionsProvider)
                postProxy("/v1/responses", responsesHandler::handle)
                postProxy("/responses", responsesHandler::handle)
                val compactHandler = CodexJsonHandler(client, requestLogger, "/responses/compact")
                postProxy("/v1/responses/compact", compactHandler::handle)
                postProxy("/responses/compact", compactHandler::handle)
                val memoriesHandler = CodexJsonHandler(client, requestLogger, "/memories/trace_summarize")
                postProxy("/v1/memories/trace_summarize", memoriesHandler::handle)
                postProxy("/memories/trace_summarize", memoriesHandler::handle)
                val chatCompletionsHandler = ChatCompletionsHandler(client, config, usageTracker, requestLogger, instructionsProvider)
                postProxy("/v1/chat/completions", chatCompletionsHandler::handle)
                postProxy("/chat/completions", chatCompletionsHandler::handle)
                val imageGenerationsHandler = CodexJsonHandler(client, requestLogger, "/images/generations")
                postProxy("/v1/images/generations", imageGenerationsHandler::handle)
                postProxy("/images/generations", imageGenerationsHandler::handle)
                val imageEditsHandler = CodexJsonHandler(client, requestLogger, "/images/edits")
                postProxy("/v1/images/edits", imageEditsHandler::handle)
                postProxy("/images/edits", imageEditsHandler::handle)
                val alphaSearchHandler = CodexJsonHandler(client, requestLogger, "/alpha/search")
                postProxy("/v1/alpha/search", alphaSearchHandler::handle)
                postProxy("/alpha/search", alphaSearchHandler::handle)
                optionsProxy("{...}", ::notFound)
                getProxy("{...}", ::notFound)
                postProxy("{...}", ::notFound)
            }
        }
    }

    fun start() {
        app.start(wait = false)
    }

    fun stop() {
        app.stop(1_000, 5_000)
    }

    @Suppress("unused")
    fun getApp(): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> = app

    private fun Routing.getProxy(path: String, handler: suspend (ProxyCall) -> Unit) {
        get(path) { dispatchCall(handler) }
    }

    private fun Routing.postProxy(path: String, handler: suspend (ProxyCall) -> Unit) {
        post(path) { dispatchCall(handler) }
    }

    private fun Routing.optionsProxy(path: String, handler: suspend (ProxyCall) -> Unit) {
        options(path) { dispatchCall(handler) }
    }

    private suspend fun RoutingContext.dispatchCall(handler: suspend (ProxyCall) -> Unit) {
        val ctx = ProxyCall(call)
        ctx.setAttribute(AccessLogFields.REQUEST_ID, requestLogger.nextRequestId())
        ctx.setAttribute(AccessLogFields.START_NANOS, System.nanoTime())
        applyCorsHeaders(ctx)
        try {
            if (isCorsPreflight(ctx)) {
                ctx.setStatus(HttpStatusCode.NoContent.value)
                ctx.call.respondText("", status = HttpStatusCode.NoContent)
                ctx.handled = true
                return
            }
            if (apiKeyStore.isEnforcing()) {
                authenticateRequest(ctx, apiKeyStore)
                if (ctx.handled) {
                    return
                }
            }
            handler(ctx)
        } catch (exception: AuthRequiredException) {
            LOG.warn("Rejected {} {}: {}", ctx.method(), ctx.path(), exception.message)
            if (!ctx.handled) JsonHelper.toErrorResponse(ctx, exception.message, 401, "authentication_error")
        } catch (exception: Exception) {
            LOG.error("Unhandled request failure for {} {}", ctx.method(), ctx.path(), exception)
            if (!ctx.handled) JsonHelper.toErrorResponse(ctx, "Unexpected server error.", 500, "server_error")
        } finally {
            if (config.consoleAccessLog) {
                logAccessLine(ctx)
            }
        }
    }

    private fun applyCorsHeaders(ctx: ProxyCall) {
        if (!config.allowAnyCors && config.allowedCorsOrigins.isEmpty()) {
            return
        }
        val origin = ctx.header(HttpHeaders.Origin)
        val allowedOrigin = if (config.allowAnyCors) {
            "*"
        } else if (origin != null && config.allowedCorsOrigins.contains(origin)) {
            origin
        } else {
            null
        }
        if (allowedOrigin == null) {
            return
        }
        ctx.responseHeader(HttpHeaders.AccessControlAllowOrigin, allowedOrigin)
        ctx.responseHeader(HttpHeaders.Vary, HttpHeaders.Origin)
        ctx.responseHeader(HttpHeaders.AccessControlAllowMethods, "GET,POST,OPTIONS")
        ctx.responseHeader(
            HttpHeaders.AccessControlAllowHeaders,
            ctx.header(HttpHeaders.AccessControlRequestHeaders) ?: "Authorization,Content-Type,X-LiteLLM-Num-Retries",
        )
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(ProxyServer::class.java)
        suspend fun authenticateRequest(ctx: ProxyCall, apiKeyStore: ApiKeyStore) {
            // Health probes are unauthenticated, matching LiteLLM's liveliness/readiness endpoints.
            if (ctx.path() == "/health" || ctx.path().startsWith("/health/")) return
            if (isCorsPreflight(ctx)) return
            val auth = ctx.header(HttpHeaders.Authorization)
            val key = if (auth != null && auth.startsWith("Bearer ")) auth.substring(7).trim() else null
            if (key != null && key == apiKeyStore.adminKey()) {
                ctx.setAttribute(ProxyCallAttributes.IS_ADMIN, true)
                ctx.setAttribute(ProxyCallAttributes.ADMIN_KEY_FINGERPRINT, ApiKeyUtils.fingerprint(key))
                return
            }
            val name = if (key != null) apiKeyStore.lookup(key) else null
            if (name == null) {
                // Reload-then-401: if the keys file changed since last load, reload it now so
                // the next request from this client succeeds. The current request gets a 401
                // which the client is expected to retry; this is intentional by design.
                apiKeyStore.reloadIfFileChanged()
                JsonHelper.toErrorResponse(ctx, "Invalid or missing API key.", 401, "auth_error")
                ctx.handled = true
            } else {
                ctx.setAttribute(ProxyCallAttributes.KEY_NAME, name)
                ctx.setAttribute(ProxyCallAttributes.KEY_FINGERPRINT, ApiKeyUtils.fingerprint(key!!))
            }
        }

        private suspend fun livenessProbe(ctx: ProxyCall) {
            // LiteLLM answers its liveliness probes with this literal JSON string.
            JsonHelper.toJsonResponse(ctx, "I'm alive!")
        }

        private suspend fun readinessProbe(ctx: ProxyCall) {
            JsonHelper.toJsonResponse(ctx, mapOf("status" to "healthy"))
        }

        private suspend fun notFound(ctx: ProxyCall) {
            JsonHelper.toErrorResponse(ctx, "Route not found.", 404, "not_found_error")
        }

        private fun isCorsPreflight(ctx: ProxyCall): Boolean {
            return ctx.method().equals(HttpMethod.Options.value, ignoreCase = true) &&
                ctx.header(HttpHeaders.Origin) != null &&
                ctx.header(HttpHeaders.AccessControlRequestMethod) != null
        }

        private fun logAccessLine(ctx: ProxyCall) {
            val startNanos = ctx.getAttribute(AccessLogFields.START_NANOS)
            val durationMillis = if (startNanos == null) {
                0L
            } else {
                Duration.ofNanos(System.nanoTime() - startNanos).toMillis()
            }
            val responseStatus = ctx.responseStatus()
            val status = accessLogStatus(ctx, responseStatus)
            System.out.printf(
                "%s %s %s %d %dms id=%s mode=%s status=%d req_bytes=%s resp_bytes=%s%n",
                Instant.now(),
                ctx.method(),
                ctx.path(),
                responseStatus,
                durationMillis,
                valueOrDefault(ctx.getAttribute(AccessLogFields.REQUEST_ID), "?"),
                valueOrDefault(ctx.getAttribute(AccessLogFields.MODE), "internal"),
                status,
                getContentLength(ctx.header(HttpHeaders.ContentLength)),
                valueOrDefault(responseBytes(ctx), "0"),
            )
        }

        private fun accessLogStatus(ctx: ProxyCall, responseStatus: Int): Int {
            val upstreamStatus = ctx.getAttribute(AccessLogFields.UPSTREAM_STATUS)
            return upstreamStatus ?: responseStatus
        }

        private fun responseBytes(ctx: ProxyCall): String {
            val recordedBytes = ctx.getAttribute(AccessLogFields.RESPONSE_BYTES)
            if (recordedBytes != null) {
                return recordedBytes.toString()
            }
            return getContentLength(ctx.responseContentLength())
        }

        private fun getContentLength(contentLength: String?): String {
            if (contentLength.isNullOrBlank()) {
                return "0"
            }
            val trimmed = contentLength.trim()
            for (char in trimmed) {
                if (!char.isDigit()) {
                    return "0"
                }
            }
            return trimmed
        }

        private fun valueOrDefault(value: Any?, defaultValue: String): String {
            if (value == null) {
                return defaultValue
            }
            val text = value.toString()
            return text.ifBlank { "-" }
        }
    }
}
