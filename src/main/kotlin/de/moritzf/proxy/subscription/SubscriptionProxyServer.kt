package de.moritzf.proxy.subscription

import de.moritzf.proxy.logging.RequestLogger
import de.moritzf.proxy.server.AccessLogFields
import de.moritzf.proxy.server.ApiKeyStore
import de.moritzf.proxy.server.HealthHandler
import de.moritzf.proxy.server.JsonHelper
import de.moritzf.proxy.server.ProxyCall
import de.moritzf.proxy.server.ProxyServer
import de.moritzf.proxy.server.RequestValidator
import de.moritzf.proxy.server.UsageHandler
import de.moritzf.proxy.server.stringPath
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
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

class SubscriptionProxyServer(
    private val port: Int,
    private val localApiKeyProvider: () -> String?,
    private val providers: () -> List<SubscriptionProxyProvider>,
    private val host: String = HOST,
    private val allowAnyCors: Boolean = false,
    private val allowedCorsOrigins: List<String> = emptyList(),
    fullRequestLogging: Boolean = false,
    requestLogDir: String = REQUEST_LOG_DIR,
) {
    private val running = AtomicBoolean(false)
    private val requestLogger = RequestLogger(fullRequestLogging, Path.of(requestLogDir))
    private val usageTracker = UsageTracker()
    private var app: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var apiKeyStore: ApiKeyStore? = null

    val isRunning: Boolean
        get() = running.get()

    fun start() {
        check(running.compareAndSet(false, true)) { "Subscription proxy is already running" }
        val localApiKey = localApiKeyProvider()?.takeIf { it.isNotBlank() }
        if (localApiKey == null) {
            running.set(false)
            error("Subscription proxy local API key is missing")
        }
        try {
            apiKeyStore = ApiKeyStore(mapOf(localApiKey to LOCAL_KEY_NAME), null, null)
            app = embeddedServer(CIO, host = host, port = port) {
                routing {
                    getProxy("/health", HealthHandler()::handle)
                    getProxy("/health/liveliness", ::livenessProbe)
                    getProxy("/health/liveness", ::livenessProbe)
                    getProxy("/health/readiness", ::readinessProbe)
                    getProxy("/v1/models", ::models)
                    getProxy("/models", ::models)
                    getProxy("/v1/model/info", ::modelInfo)
                    getProxy("/model/info", ::modelInfo)
                    getProxy("/v1/usage", UsageHandler(usageTracker)::handle)
                    postProxy("/v1/chat/completions", ::inference)
                    postProxy("/chat/completions", ::inference)
                    postProxy("/v1/responses", ::inference)
                    postProxy("/responses", ::inference)
                    postProxy("/v1/messages", ::inference)
                    postProxy("/messages", ::inference)
                    optionsProxy("{...}", ::notFound)
                    getProxy("{...}", ::notFound)
                    postProxy("{...}", ::notFound)
                }
            }.also { it.start(wait = false) }
        } catch (exception: Exception) {
            running.set(false)
            apiKeyStore = null
            app = null
            throw exception
        }
    }

    fun stop() {
        val current = app
        app = null
        apiKeyStore = null
        running.set(false)
        current?.stop(1_000, 5_000)
    }

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
            apiKeyStore?.let { authenticateRequest(ctx, it) }
            if (ctx.handled) return
            handler(ctx)
        } catch (exception: Exception) {
            LOG.error("Unhandled subscription proxy request failure for {} {}", ctx.method(), ctx.path(), exception)
            if (!ctx.handled) JsonHelper.toErrorResponse(ctx, "Unexpected server error.", 500, "server_error")
        }
    }

    private suspend fun models(ctx: ProxyCall) {
        val catalog = catalog()
        val data = catalog.models.filter(::isOpenAiCompatibleModel).map { model ->
            linkedMapOf<String, Any>(
                "id" to model.localId,
                "object" to "model",
                "created" to 0,
                "owned_by" to model.providerId,
            )
        }
        JsonHelper.toJsonResponse(ctx, mapOf("object" to "list", "data" to data))
    }

    private suspend fun modelInfo(ctx: ProxyCall) {
        JsonHelper.toJsonResponse(ctx, mapOf("data" to catalog().models.map(::liteLlmInfo)))
    }

    private suspend fun inference(ctx: ProxyCall) {
        val route = routeForPath(ctx.path()) ?: run {
            JsonHelper.toErrorResponse(ctx, "Route not found.", 404, "not_found_error")
            return
        }
        AccessLogFields.mode(ctx, if (ctx.header(HttpHeaders.Accept)?.contains("text/event-stream") == true) "stream" else "proxy")
        val requestId = ctx.getAttribute(AccessLogFields.REQUEST_ID) ?: requestLogger.nextRequestId()
        val body = RequestValidator.parseLoggedJsonObject(ctx, requestLogger, requestId) ?: return
        val catalog = catalog()
        val requestedModel = body.stringPath("model").trim().takeIf { it.isNotBlank() }
        val model = if (requestedModel == null) {
            catalog.defaultModel(route) ?: run {
                JsonHelper.toErrorResponse(ctx, "No proxy models are available for ${route.normalizedPath}.", 503, "configuration_error")
                return
            }
        } else {
            catalog.resolve(requestedModel, route) ?: run {
                JsonHelper.toErrorResponse(ctx, "Unknown proxy model: $requestedModel", 400, "invalid_request_error")
                return
            }
        }
        if (route !in model.supportedRoutes) {
            JsonHelper.toErrorResponse(ctx, "Model ${model.localId} does not support ${route.normalizedPath}.", 400, "invalid_request_error")
            return
        }
        val provider = catalog().providerFor(model) ?: run {
            JsonHelper.toErrorResponse(ctx, "Provider for ${model.localId} is not configured.", 503, "configuration_error")
            return
        }
        provider.handle(ctx, SubscriptionProxyRequest(route, requestId, model, body))
    }

    private fun catalog(): SubscriptionModelCatalog = SubscriptionModelCatalog(providers())

    private fun applyCorsHeaders(ctx: ProxyCall) {
        val origin = ctx.header(HttpHeaders.Origin)
        val allowedOrigin = if (allowAnyCors) {
            "*"
        } else if (origin != null && ProxyServer.isAllowedCorsOrigin(origin, allowedCorsOrigins)) {
            origin
        } else {
            null
        } ?: return
        ctx.responseHeader(HttpHeaders.AccessControlAllowOrigin, allowedOrigin)
        ctx.responseHeader(HttpHeaders.Vary, HttpHeaders.Origin)
        ctx.responseHeader(HttpHeaders.AccessControlAllowMethods, "GET,POST,OPTIONS")
        ctx.responseHeader(
            HttpHeaders.AccessControlAllowHeaders,
            ctx.header(HttpHeaders.AccessControlRequestHeaders) ?: "Authorization,Content-Type,X-LiteLLM-Num-Retries,x-api-key",
        )
    }

    private suspend fun authenticateRequest(ctx: ProxyCall, apiKeyStore: ApiKeyStore) {
        if (ctx.path() == "/health" || ctx.path().startsWith("/health/")) return
        if (isCorsPreflight(ctx)) return
        val key = localApiKey(ctx)
        if (key != null && key == apiKeyStore.adminKey()) {
            ctx.setAttribute(de.moritzf.proxy.server.ProxyCallAttributes.IS_ADMIN, true)
            ctx.setAttribute(de.moritzf.proxy.server.ProxyCallAttributes.ADMIN_KEY_FINGERPRINT, ApiKeyUtils.fingerprint(key))
            return
        }
        val name = if (key != null) apiKeyStore.lookup(key) else null
        if (name == null) {
            apiKeyStore.reloadIfFileChanged()
            JsonHelper.toErrorResponse(ctx, "Invalid or missing API key.", 401, "auth_error")
            ctx.handled = true
            return
        }
        ctx.setAttribute(de.moritzf.proxy.server.ProxyCallAttributes.KEY_NAME, name)
        ctx.setAttribute(de.moritzf.proxy.server.ProxyCallAttributes.KEY_FINGERPRINT, ApiKeyUtils.fingerprint(key!!))
    }

    private fun localApiKey(ctx: ProxyCall): String? {
        val auth = ctx.header(HttpHeaders.Authorization)
        val bearer = if (auth != null && auth.startsWith("Bearer ")) auth.substring(7).trim() else null
        return bearer?.takeIf { it.isNotBlank() }
            ?: ctx.header("x-api-key")?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun liteLlmInfo(model: SubscriptionProxyModel): Map<String, Any> {
        val litellmParams = linkedMapOf<String, Any>(
            "model" to model.localId,
            "custom_llm_provider" to model.litellmProvider,
        )
        val modelInfo = linkedMapOf<String, Any>(
            "id" to model.localId,
            "mode" to modelMode(model),
            "litellm_provider" to model.litellmProvider,
            "supported_endpoints" to supportedEndpoints(model),
            "supported_routes" to model.supportedRoutes.map { it.normalizedPath },
            "supports_anthropic_messages" to (SubscriptionProxyRoute.ANTHROPIC_MESSAGES in model.supportedRoutes),
            "supports_function_calling" to model.supportsFunctionCalling,
            "supports_parallel_function_calling" to model.supportsParallelFunctionCalling,
            "supports_tool_choice" to model.supportsToolChoice,
            "supports_vision" to model.supportsVision,
            "supports_prompt_caching" to model.supportsPromptCaching,
            "input_cost_per_token" to 0.0,
            "output_cost_per_token" to 0.0,
            "is_default" to model.isDefault,
        )
        model.maxInputTokens?.let { modelInfo["max_input_tokens"] = it }
        model.maxOutputTokens?.let {
            modelInfo["max_output_tokens"] = it
            modelInfo["max_tokens"] = it
        }
        return linkedMapOf(
            "id" to model.localId,
            "model_name" to model.localId,
            "litellm_params" to litellmParams,
            "model_info" to modelInfo,
        )
    }

    private fun modelMode(model: SubscriptionProxyModel): String {
        return if (model.supportedRoutes.any { it != SubscriptionProxyRoute.ANTHROPIC_MESSAGES }) {
            "chat"
        } else {
            "messages"
        }
    }

    private fun supportedEndpoints(model: SubscriptionProxyModel): List<String> {
        return model.supportedRoutes.map { route -> "/v1${route.normalizedPath}" }
    }

    private fun isOpenAiCompatibleModel(model: SubscriptionProxyModel): Boolean {
        return model.supportedRoutes.any { route ->
            route == SubscriptionProxyRoute.CHAT_COMPLETIONS || route == SubscriptionProxyRoute.RESPONSES
        }
    }

    companion object {
        private const val HOST = "127.0.0.1"
        private const val LOCAL_KEY_NAME = "local"
        val REQUEST_LOG_DIR: String = Path.of("logs", "subscription-proxy-requests")
            .toAbsolutePath()
            .normalize()
            .toString()
        private val LOG = LoggerFactory.getLogger(SubscriptionProxyServer::class.java)

        private suspend fun livenessProbe(ctx: ProxyCall) {
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

        private fun routeForPath(path: String): SubscriptionProxyRoute? {
            val normalized = path.removePrefix("/v1")
            return SubscriptionProxyRoute.entries.firstOrNull { it.normalizedPath == normalized }
        }
    }
}
