package de.moritzf.proxy.server

import de.moritzf.proxy.auth.AuthRequiredException
import de.moritzf.proxy.config.ServerConfig
import de.moritzf.proxy.logging.RequestLogger
import de.moritzf.proxy.model.CodexInstructionsProvider
import de.moritzf.proxy.model.ModelResolver
import de.moritzf.proxy.transport.CodexHttpClient
import de.moritzf.proxy.usage.UsageTracker
import de.moritzf.proxy.util.ApiKeyUtils
import io.javalin.Javalin
import io.javalin.http.Context
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory

class ProxyServer(
    private val config: ServerConfig,
    client: CodexHttpClient,
    modelResolver: ModelResolver,
    usageTracker: UsageTracker,
    apiKeyStore: ApiKeyStore,
) {
    private val app: Javalin

    init {
        if (config.requiresApiKeyEnforcement() && !apiKeyStore.isEnforcing()) {
            throw IllegalStateException("API key enforcement is required when binding to a non-loopback host: ${config.host()}")
        }
        val requestLogger = RequestLogger(config.fullRequestLogging(), Path.of(config.requestLogDir()))
        val instructionsProvider = if (config.codexInstructionsMode() == "latest-codex") {
            CodexInstructionsProvider(
                CodexInstructionsProvider.Mode.LATEST_CODEX,
                config.instructions(),
                Path.of(config.codexInstructionsCacheDir()),
                Duration.ofMinutes(15),
                client.getHttpClient(),
            )
        } else {
            CodexInstructionsProvider(config.instructions())
        }

        app = Javalin.create { javalinConfig ->
            javalinConfig.concurrency.useVirtualThreads = true
            javalinConfig.startup.showJavalinBanner = false

            if (config.allowAnyCors() || config.allowedCorsOrigins().isNotEmpty()) {
                javalinConfig.bundledPlugins.enableCors { cors ->
                    cors.addRule { rule ->
                        if (config.allowAnyCors()) {
                            rule.anyHost()
                        } else {
                            val first = config.allowedCorsOrigins().first()
                            val rest = config.allowedCorsOrigins().drop(1).toTypedArray()
                            rule.allowHost(first, *rest)
                        }
                    }
                }
            }

            javalinConfig.routes.before { ctx ->
                ctx.attribute(AccessLogFields.REQUEST_ID, requestLogger.nextRequestId())
                ctx.attribute(AccessLogFields.START_NANOS, System.nanoTime())
            }
            // Per-request stdout lines are CLI behavior; embedders (the IDE plugin) keep
            // their process output clean and disable this via config.
            if (config.consoleAccessLog()) {
                javalinConfig.routes.after(::logAccessLine)
            }

            // API key enforcement (opt-in: only when keys are configured)
            // Enforcement is evaluated once at startup. Keys can be hot-reloaded (which keys
            // are valid changes), but enforcement cannot be toggled on/off without a restart.
            if (apiKeyStore.isEnforcing()) {
                javalinConfig.routes.beforeMatched { ctx -> authenticateRequest(ctx, apiKeyStore) }
            }

            // Routes. LiteLLM serves every API route both with and without the /v1
            // prefix, and clients differ in which variant they call (Junie appends
            // /v1/... to a prefix-less base URL) - mirror that here.
            javalinConfig.routes.get("/health", HealthHandler())
            javalinConfig.routes.get("/health/liveliness", ::livenessProbe)
            javalinConfig.routes.get("/health/liveness", ::livenessProbe)
            javalinConfig.routes.get("/health/readiness", ::readinessProbe)
            val modelsHandler = ModelsHandler(modelResolver)
            javalinConfig.routes.get("/v1/models", modelsHandler)
            javalinConfig.routes.get("/models", modelsHandler)
            val modelInfoHandler = LiteLlmModelInfoHandler(modelResolver)
            javalinConfig.routes.get("/v1/model/info", modelInfoHandler)
            javalinConfig.routes.get("/model/info", modelInfoHandler)
            javalinConfig.routes.get("/v1/usage", UsageHandler(usageTracker))
            val responsesHandler = ResponsesHandler(client, config, usageTracker, requestLogger, instructionsProvider)
            javalinConfig.routes.post("/v1/responses", responsesHandler)
            javalinConfig.routes.post("/responses", responsesHandler)
            val compactHandler = CodexJsonHandler(client, requestLogger, "/responses/compact")
            javalinConfig.routes.post("/v1/responses/compact", compactHandler)
            javalinConfig.routes.post("/responses/compact", compactHandler)
            val memoriesHandler = CodexJsonHandler(client, requestLogger, "/memories/trace_summarize")
            javalinConfig.routes.post("/v1/memories/trace_summarize", memoriesHandler)
            javalinConfig.routes.post("/memories/trace_summarize", memoriesHandler)
            val chatCompletionsHandler = ChatCompletionsHandler(client, config, usageTracker, requestLogger, instructionsProvider)
            javalinConfig.routes.post("/v1/chat/completions", chatCompletionsHandler)
            javalinConfig.routes.post("/chat/completions", chatCompletionsHandler)
            val imageGenerationsHandler = CodexJsonHandler(client, requestLogger, "/images/generations")
            javalinConfig.routes.post("/v1/images/generations", imageGenerationsHandler)
            javalinConfig.routes.post("/images/generations", imageGenerationsHandler)
            val imageEditsHandler = CodexJsonHandler(client, requestLogger, "/images/edits")
            javalinConfig.routes.post("/v1/images/edits", imageEditsHandler)
            javalinConfig.routes.post("/images/edits", imageEditsHandler)
            val alphaSearchHandler = CodexJsonHandler(client, requestLogger, "/alpha/search")
            javalinConfig.routes.post("/v1/alpha/search", alphaSearchHandler)
            javalinConfig.routes.post("/alpha/search", alphaSearchHandler)

            // Missing upstream credentials surface as 401 so clients show the real cause
            // (e.g. "OpenAI login required") instead of a generic server error.
            javalinConfig.routes.exception(AuthRequiredException::class.java) { exception, ctx ->
                LOG.warn("Rejected {} {}: {}", ctx.method(), ctx.path(), exception.message)
                JsonHelper.toErrorResponse(ctx, exception.message, 401, "authentication_error")
            }

            // Global exception handler.
            javalinConfig.routes.exception(Exception::class.java) { exception, ctx ->
                LOG.error("Unhandled request failure for {} {}", ctx.method(), ctx.path(), exception)
                JsonHelper.toErrorResponse(ctx, "Unexpected server error.", 500, "server_error")
            }

            // 404 handler.
            javalinConfig.routes.error(404) { ctx ->
                JsonHelper.toErrorResponse(ctx, "Route not found.", 404, "not_found_error")
            }
        }
    }

    fun start() {
        app.start(config.host(), config.port())
    }

    fun stop() {
        app.stop()
    }

    @Suppress("unused")
    fun getApp(): Javalin = app

    companion object {
        private val LOG = LoggerFactory.getLogger(ProxyServer::class.java)

        @JvmStatic
        fun authenticateRequest(ctx: Context, apiKeyStore: ApiKeyStore) {
            // Health probes are unauthenticated, matching LiteLLM's liveliness/readiness endpoints.
            if (ctx.path() == "/health" || ctx.path().startsWith("/health/")) return
            if (isCorsPreflight(ctx)) return
            val auth = ctx.header("Authorization")
            val key = if (auth != null && auth.startsWith("Bearer ")) auth.substring(7).trim() else null
            if (key != null && key == apiKeyStore.adminKey()) {
                ctx.attribute("isAdmin", true)
                ctx.attribute("adminKeyFingerprint", ApiKeyUtils.fingerprint(key))
                return
            }
            val name = if (key != null) apiKeyStore.lookup(key) else null
            if (name == null) {
                // Reload-then-401: if the keys file changed since last load, reload it now so
                // the next request from this client succeeds. The current request gets a 401
                // which the client is expected to retry; this is intentional by design.
                apiKeyStore.reloadIfFileChanged()
                JsonHelper.toErrorResponse(ctx, "Invalid or missing API key.", 401, "auth_error")
                ctx.skipRemainingHandlers()
            } else {
                ctx.attribute("keyName", name)
                ctx.attribute("keyFingerprint", ApiKeyUtils.fingerprint(key!!))
            }
        }

        private fun livenessProbe(ctx: Context) {
            // LiteLLM answers its liveliness probes with this literal JSON string.
            JsonHelper.toJsonResponse(ctx, "I'm alive!")
        }

        private fun readinessProbe(ctx: Context) {
            JsonHelper.toJsonResponse(ctx, mapOf("status" to "healthy"))
        }

        private fun isCorsPreflight(ctx: Context): Boolean {
            return ctx.method().name.equals("OPTIONS", ignoreCase = true) &&
                ctx.header("Origin") != null &&
                ctx.header("Access-Control-Request-Method") != null
        }

        private fun logAccessLine(ctx: Context) {
            val startNanos = ctx.attribute<Long>(AccessLogFields.START_NANOS)
            val durationMillis = if (startNanos == null) {
                0L
            } else {
                Duration.ofNanos(System.nanoTime() - startNanos).toMillis()
            }
            val responseStatus = ctx.statusCode()
            val status = accessLogStatus(ctx, responseStatus)
            System.out.printf(
                "%s %s %s %d %dms id=%s mode=%s status=%d req_bytes=%s resp_bytes=%s%n",
                Instant.now(),
                ctx.method(),
                ctx.path(),
                responseStatus,
                durationMillis,
                valueOrDefault(ctx.attribute<Any>(AccessLogFields.REQUEST_ID), "?"),
                valueOrDefault(ctx.attribute<Any>(AccessLogFields.MODE), "internal"),
                status,
                getContentLength(ctx.header("Content-Length")),
                valueOrDefault(responseBytes(ctx), "0"),
            )
        }

        private fun accessLogStatus(ctx: Context, responseStatus: Int): Int {
            val upstreamStatus = ctx.attribute<Int>(AccessLogFields.UPSTREAM_STATUS)
            return upstreamStatus ?: responseStatus
        }

        private fun responseBytes(ctx: Context): String {
            val recordedBytes = ctx.attribute<Long>(AccessLogFields.RESPONSE_BYTES)
            if (recordedBytes != null) {
                return recordedBytes.toString()
            }
            return getContentLength(ctx.res().getHeader("Content-Length"))
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
