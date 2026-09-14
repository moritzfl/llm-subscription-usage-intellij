package de.moritzf.quota.github.proxy

import de.moritzf.proxy.logging.RequestLogger
import de.moritzf.proxy.model.CodexInstructionsProvider
import de.moritzf.proxy.server.ChatCompletionsHandler
import de.moritzf.proxy.server.JsonHelper
import de.moritzf.proxy.server.MutableJsonObject
import de.moritzf.proxy.server.ProxyCall
import de.moritzf.proxy.server.remove
import de.moritzf.proxy.subscription.PassThroughSubscriptionProxyProvider
import de.moritzf.proxy.subscription.SubscriptionProxyModel
import de.moritzf.proxy.subscription.SubscriptionProxyProvider
import de.moritzf.proxy.subscription.SubscriptionProxyRequest
import de.moritzf.proxy.subscription.SubscriptionProxyRoute
import de.moritzf.proxy.transport.UrlResolver
import de.moritzf.proxy.usage.UsageTracker
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

class GitHubCopilotSubscriptionProxyProvider(
    private val accessTokenProvider: () -> String?,
    private val tokenRefresher: (staleAccessToken: String?) -> String? = { null },
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build(),
    private val upstreamBaseUri: URI = DEFAULT_UPSTREAM_BASE_URI,
    private val persistentModelCacheProvider: () -> String? = { null },
    private val persistentModelCacheSaver: (String?) -> Unit = {},
    private val missingModelRetryDelays: List<Duration> = DEFAULT_MISSING_MODEL_RETRY_DELAYS,
    private val modelCacheTtl: kotlin.time.Duration = DEFAULT_CACHE_TTL,
    fullRequestLogging: Boolean = false,
    requestLogDir: String = DEFAULT_REQUEST_LOG_DIR,
) : SubscriptionProxyProvider {
    private val requestLogger = RequestLogger(fullRequestLogging, Path.of(requestLogDir))
    private val modelCatalog = GitHubCopilotModelCatalog(
        accessTokenProvider = accessTokenProvider,
        httpClient = httpClient,
        upstreamBaseUri = upstreamBaseUri,
        persistentModelCacheProvider = persistentModelCacheProvider,
        persistentModelCacheSaver = persistentModelCacheSaver,
        missingModelRetryDelays = missingModelRetryDelays,
        modelCacheTtl = modelCacheTtl,
    )
    private val claudeBridge = GitHubCopilotClaudeChatBridge()
    private val chatCompletionsHandler = ChatCompletionsHandler(
        requestLogger = requestLogger,
        usageTracker = UsageTracker(),
        responsesRequester = ChatCompletionsHandler.ResponsesRequester(::sendResponsesForChatCompletion),
        store = false,
        configuredModels = null,
        fullRequestLogging = fullRequestLogging,
        forwardPromptCacheHeaders = false,
        instructionsProvider = CodexInstructionsProvider(GitHubCopilotProxyIds.DEFAULT_RESPONSES_INSTRUCTIONS),
        responsesBodyTransformer = ::responsesChatBody,
    )
    private val delegate = PassThroughSubscriptionProxyProvider(
        id = ID,
        displayName = DISPLAY_NAME,
        litellmProvider = GitHubCopilotProxyIds.LITELLM_PROVIDER,
        baseUri = upstreamBaseUri,
        accessTokenProvider = accessTokenProvider,
        tokenRefresher = tokenRefresher,
        modelMappingsProvider = ::modelMappings,
        defaultHeaders = mapOf(
            "Accept" to "application/json",
            "User-Agent" to GitHubCopilotProxyIds.USER_AGENT,
            "Copilot-Integration-Id" to GitHubCopilotProxyIds.COPILOT_INTEGRATION_ID,
            "Editor-Version" to GitHubCopilotProxyIds.EDITOR_VERSION,
            "Editor-Plugin-Version" to GitHubCopilotProxyIds.EDITOR_PLUGIN_VERSION,
            "X-GitHub-Api-Version" to GitHubCopilotProxyIds.API_VERSION,
            "Openai-Intent" to "conversation-edits",
            "x-initiator" to "user",
        ),
        forwardedRequestHeadersTransformer = ::forwardedRequestHeaders,
        requestHeadersProvider = ::requestHeaders,
        requestBodyTransformer = ::requestBody,
        upstreamRouteProvider = ::upstreamRoute,
        jsonResponseTransformer = ::openAiChatJsonResponse,
        sseDataTransformer = ::openAiChatSseData,
        sseLineTransformer = ::openAiChatSseLine,
        sseStreamComplete = { claudeBridge.clearStreamState(it.requestId) },
        httpClient = httpClient,
        requestLogger = requestLogger,
    )

    override val id: String = ID
    override val displayName: String = DISPLAY_NAME

    override fun isConfigured(): Boolean = delegate.isConfigured()

    override fun models() = delegate.models()

    override fun fallbackModel(localId: String, route: SubscriptionProxyRoute) = prefixedFallbackModel(localId, route)

    override suspend fun handle(ctx: ProxyCall, request: SubscriptionProxyRequest) {
        if (shouldBridgeChatToResponses(request)) {
            if (accessTokenProvider().trimmedOrNull() == null) {
                JsonHelper.toErrorResponse(ctx, "$DISPLAY_NAME login required.", 401, "authentication_error")
                return
            }
            chatCompletionsHandler.handleParsed(ctx, request.requestId, request.bodyWithUpstreamModel())
            return
        }
        delegate.handle(ctx, request)
    }

    private fun shouldBridgeChatToResponses(request: SubscriptionProxyRequest): Boolean {
        return request.route == SubscriptionProxyRoute.CHAT_COMPLETIONS &&
            SubscriptionProxyRoute.RESPONSES in request.model.supportedRoutes &&
            shouldBridgeResponsesModel(request.model.upstreamId)
    }

    private fun prefixedFallbackModel(localId: String, route: SubscriptionProxyRoute): SubscriptionProxyModel? {
        val upstreamId = fallbackUpstreamId(localId) ?: return null
        return SubscriptionProxyModel(
            localId = localId,
            upstreamId = upstreamId,
            providerId = id,
            providerName = displayName,
            litellmProvider = GitHubCopilotProxyIds.LITELLM_PROVIDER,
            supportedRoutes = setOf(route),
            supportsFunctionCalling = true,
            supportsToolChoice = true,
            supportsVision = true,
        )
    }

    private fun modelMappings(): List<PassThroughSubscriptionProxyProvider.ModelMapping> {
        return modelCatalog.models().map { model ->
            PassThroughSubscriptionProxyProvider.ModelMapping(
                localId = PREFIX + model.id,
                upstreamId = model.id,
                supportedRoutes = localSupportedRoutes(model),
                supportsFunctionCalling = model.supportsFunctionCalling,
                supportsToolChoice = true,
                supportsVision = model.supportsVision,
                maxInputTokens = model.maxInputTokens,
                maxOutputTokens = model.maxOutputTokens,
                isDefault = model.isDefault,
            )
        }
    }

    private fun localSupportedRoutes(model: GitHubCopilotRemoteModel): Set<SubscriptionProxyRoute> {
        return if (SubscriptionProxyRoute.RESPONSES in model.supportedRoutes && shouldBridgeResponsesModel(model.id)) {
            model.supportedRoutes + SubscriptionProxyRoute.CHAT_COMPLETIONS
        } else if (SubscriptionProxyRoute.ANTHROPIC_MESSAGES in model.supportedRoutes && isClaudeModel(model.id)) {
            model.supportedRoutes + SubscriptionProxyRoute.CHAT_COMPLETIONS
        } else {
            model.supportedRoutes
        }
    }

    private fun SubscriptionProxyRequest.bodyWithUpstreamModel(): JsonObject {
        return buildJsonObject {
            body.forEach { (key, value) ->
                put(key, if (key == "model") JsonPrimitive(model.upstreamId) else value)
            }
            if ("model" !in body) put("model", model.upstreamId)
        }
    }

    private fun requestHeaders(request: SubscriptionProxyRequest): Map<String, String> {
        return if (containsImageInput(request.body)) mapOf("Copilot-Vision-Request" to "true") else emptyMap()
    }

    private fun forwardedRequestHeaders(
        request: SubscriptionProxyRequest,
        headers: Map<String, String>,
    ): Map<String, String> {
        if (upstreamRoute(request) != SubscriptionProxyRoute.ANTHROPIC_MESSAGES) return headers
        return headers.filterKeys { !it.equals("anthropic-beta", ignoreCase = true) }
    }

    private fun upstreamRoute(request: SubscriptionProxyRequest): SubscriptionProxyRoute {
        return if (claudeBridge.shouldBridge(request)) {
            SubscriptionProxyRoute.ANTHROPIC_MESSAGES
        } else {
            request.route
        }
    }

    private fun requestBody(request: SubscriptionProxyRequest, body: JsonObject): JsonObject {
        if (claudeBridge.shouldBridge(request)) {
            return claudeBridge.openAiChatToAnthropicMessagesBody(request, body)
        }
        if (request.route == SubscriptionProxyRoute.RESPONSES && request.model.upstreamId.startsWith("gpt-")) {
            return body.remove("max_output_tokens")
        }
        if (request.route != SubscriptionProxyRoute.ANTHROPIC_MESSAGES) return body
        return buildJsonObject {
            body.forEach { (key, value) ->
                if (key !in GitHubCopilotProxyIds.UNSUPPORTED_MESSAGES_BODY_FIELDS) put(key, value)
            }
        }
    }

    private fun openAiChatJsonResponse(request: SubscriptionProxyRequest, body: String): String {
        if (claudeBridge.shouldBridge(request)) return claudeBridge.anthropicMessageToOpenAiChat(request, body)
        return openAiChatEnvelope(request, body, "chat.completion")
    }

    private fun openAiChatSseData(request: SubscriptionProxyRequest, data: String): String {
        if (claudeBridge.shouldBridge(request)) return claudeBridge.anthropicSseDataToOpenAiChat(request, data)
        return openAiChatEnvelope(request, data, "chat.completion.chunk")
    }

    private fun openAiChatSseLine(request: SubscriptionProxyRequest, line: String): String? {
        return claudeBridge.openAiChatSseLine(request, line)
    }

    private fun openAiChatEnvelope(request: SubscriptionProxyRequest, body: String, objectType: String): String {
        if (request.route != SubscriptionProxyRoute.CHAT_COMPLETIONS || body.isBlank()) return body
        val root = JsonHelper.parseToJsonElementOrNull(body) as? JsonObject ?: return body
        if ("error" in root) return body
        val normalized = buildJsonObject {
            put("id", root["id"] ?: JsonPrimitive("chatcmpl-${request.requestId}"))
            put("object", root["object"] ?: JsonPrimitive(objectType))
            put("created", root["created"] ?: JsonPrimitive(System.currentTimeMillis() / 1000L))
            put("model", root["model"] ?: JsonPrimitive(request.model.upstreamId))
            put("choices", root["choices"] ?: JsonArray(emptyList()))
            root.forEach { (key, value) ->
                if (key !in GitHubCopilotProxyIds.OPENAI_CHAT_ENVELOPE_FIELDS && value != JsonNull) put(key, value)
            }
        }
        return JsonHelper.encodeToString(normalized)
    }

    private fun responsesChatBody(body: MutableJsonObject) {
        body.remove("store")
        body.remove("prompt_cache_key")
        val model = (body.get("model") as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (model.startsWith("mai-code-")) {
            body.remove("temperature")
        }
        if (model.startsWith("gpt-")) {
            body.remove("max_output_tokens")
        }
    }

    private fun sendResponsesForChatCompletion(
        payload: String,
        requestId: String,
        @Suppress("UNUSED_PARAMETER") promptCacheKey: String?,
    ): HttpResponse<InputStream> {
        val token = accessTokenProvider().trimmedOrNull() ?: error("$DISPLAY_NAME login required.")
        var response = sendResponsesRequest(payload, requestId, token)
        if (response.statusCode() == 401) {
            val refreshed = refreshAfterUnauthorized(token)
            if (refreshed != null) {
                runCatching { response.body().close() }
                response = sendResponsesRequest(payload, requestId, refreshed)
            }
        }
        return response
    }

    private fun sendResponsesRequest(payload: String, requestId: String, accessToken: String): HttpResponse<InputStream> {
        val headers = linkedMapOf(
            "Authorization" to "Bearer $accessToken",
            "Accept" to "application/json",
            "User-Agent" to GitHubCopilotProxyIds.USER_AGENT,
            "Copilot-Integration-Id" to GitHubCopilotProxyIds.COPILOT_INTEGRATION_ID,
            "Editor-Version" to GitHubCopilotProxyIds.EDITOR_VERSION,
            "Editor-Plugin-Version" to GitHubCopilotProxyIds.EDITOR_PLUGIN_VERSION,
            "X-GitHub-Api-Version" to GitHubCopilotProxyIds.API_VERSION,
            "Openai-Intent" to "conversation-edits",
            "x-initiator" to "user",
            "Content-Type" to JsonHelper.JSON_CONTENT_TYPE,
        )
        if (containsImageInput(JsonHelper.parseToJsonElementOrNull(payload))) {
            headers["Copilot-Vision-Request"] = "true"
        }
        val targetUrl = UrlResolver.resolveTargetUrl("/responses", upstreamBaseUri.toString())
        val builder = HttpRequest.newBuilder(URI.create(targetUrl))
            .timeout(Duration.ofSeconds(30))
        headers.forEach { (name, value) -> builder.header(name, value) }
        requestLogger.logUpstreamRequest(requestId, "POST", "/responses", headers, payload)
        return httpClient.send(builder.POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofInputStream())
    }

    private fun refreshAfterUnauthorized(staleToken: String): String? {
        return try {
            tokenRefresher(staleToken).trimmedOrNull()?.takeIf { it != staleToken }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val ID = "github"
        const val PREFIX = "gh-"
        val DEFAULT_UPSTREAM_BASE_URI: URI = GitHubCopilotProxyIds.DEFAULT_UPSTREAM_BASE_URI
        private val DEFAULT_CACHE_TTL = GitHubCopilotProxyIds.DEFAULT_CACHE_TTL
        private val DEFAULT_MISSING_MODEL_RETRY_DELAYS = GitHubCopilotProxyIds.DEFAULT_MISSING_MODEL_RETRY_DELAYS
        private val DEFAULT_REQUEST_LOG_DIR = GitHubCopilotProxyIds.DEFAULT_REQUEST_LOG_DIR
        private const val DISPLAY_NAME = "GitHub Copilot"
    }
}
