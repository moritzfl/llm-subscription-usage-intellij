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
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.booleanOrNull
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
    private val chatCompletionsHandler = ChatCompletionsHandler(
        requestLogger = requestLogger,
        usageTracker = UsageTracker(),
        responsesRequester = ChatCompletionsHandler.ResponsesRequester(::sendResponsesForChatCompletion),
        store = false,
        configuredModels = null,
        fullRequestLogging = fullRequestLogging,
        forwardPromptCacheHeaders = false,
        instructionsProvider = CodexInstructionsProvider(DEFAULT_RESPONSES_INSTRUCTIONS),
        responsesBodyTransformer = ::responsesChatBody,
    )
    private val delegate = PassThroughSubscriptionProxyProvider(
        id = ID,
        displayName = DISPLAY_NAME,
        litellmProvider = LITELLM_PROVIDER,
        baseUri = upstreamBaseUri,
        accessTokenProvider = accessTokenProvider,
        tokenRefresher = tokenRefresher,
        modelMappingsProvider = ::modelMappings,
        defaultHeaders = mapOf(
            "Accept" to "application/json",
            "User-Agent" to USER_AGENT,
            "Copilot-Integration-Id" to COPILOT_INTEGRATION_ID,
            "Editor-Version" to EDITOR_VERSION,
            "Editor-Plugin-Version" to EDITOR_PLUGIN_VERSION,
            "X-GitHub-Api-Version" to API_VERSION,
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
        httpClient = httpClient,
        requestLogger = requestLogger,
    )

    @Volatile
    private var modelCache: ModelCache? = null
    private val streamToolCallIndexes = ConcurrentHashMap<String, MutableMap<Int, Int>>()
    private val remoteImageHttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    private val missingModelRetryLock = Any()
    @Volatile
    private var missingModelRetry: MissingModelRetry? = null

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
            litellmProvider = LITELLM_PROVIDER,
            supportedRoutes = setOf(route),
            supportsFunctionCalling = true,
            supportsToolChoice = true,
            supportsVision = true,
        )
    }

    private fun modelMappings(): List<PassThroughSubscriptionProxyProvider.ModelMapping> {
        return remoteModels().map { model ->
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

    private fun localSupportedRoutes(model: RemoteModel): Set<SubscriptionProxyRoute> {
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

    @OptIn(ExperimentalTime::class)
    private fun remoteModels(): List<RemoteModel> {
        val now = Clock.System.now()
        val cached = modelCache ?: loadPersistedModelCache(now)
        if (cached != null && now - cached.fetchedAt < modelCacheTtl) {
            return cached.models
        }
        val token = accessTokenProvider().trimmedOrNull() ?: return emptyList()
        val fetched = runCatching { fetchModels(token) }.getOrDefault(emptyList())
        if (fetched.isEmpty()) {
            if (cached != null) modelCache = cached.copy(fetchedAt = now)
            return cached?.models.orEmpty()
        }

        if (cached == null) {
            cacheModels(fetched, now)
            return fetched
        }

        val fetchedIds = fetched.mapTo(mutableSetOf()) { it.id }
        val missingCachedModels = cached.models.filter { it.id !in fetchedIds }
        if (missingCachedModels.isEmpty()) {
            clearMissingModelRetry()
            cacheModels(fetched, now)
            return fetched
        }

        val protectedModels = mergeModels(fetched, missingCachedModels)
        modelCache = ModelCache(protectedModels, now)
        startMissingModelRetry(token, missingCachedModels, fetched)
        return protectedModels
    }

    @OptIn(ExperimentalTime::class)
    private fun loadPersistedModelCache(now: Instant): ModelCache? {
        val models = loadPersistedModels()
        if (models.isEmpty()) return null
        return ModelCache(models, now - modelCacheTtl)
    }

    @OptIn(ExperimentalTime::class)
    private fun cacheModels(models: List<RemoteModel>, fetchedAt: Instant = Clock.System.now()) {
        modelCache = ModelCache(models, fetchedAt)
        savePersistedModels(models)
    }

    private fun loadPersistedModels(): List<RemoteModel> {
        val raw = runCatching { persistentModelCacheProvider() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        val root = JsonHelper.parseToJsonElementOrNull(raw) as? JsonObject ?: return emptyList()
        val models = root["models"] as? JsonArray ?: return emptyList()
        return models.mapNotNull { parseCachedRemoteModel(it) }
            .filter { it.supportedRoutes.isNotEmpty() }
            .distinctBy { it.id }
    }

    private fun savePersistedModels(models: List<RemoteModel>) {
        val payload = buildJsonObject {
            put("version", 1)
            put(
                "models",
                JsonArray(models.map { model ->
                    buildJsonObject {
                        put("id", model.id)
                        put("supportedRoutes", JsonArray(model.supportedRoutes.map { JsonPrimitive(it.normalizedPath) }))
                        put("supportsFunctionCalling", model.supportsFunctionCalling)
                        put("supportsVision", model.supportsVision)
                        model.maxInputTokens?.let { put("maxInputTokens", it) }
                        model.maxOutputTokens?.let { put("maxOutputTokens", it) }
                        put("isDefault", model.isDefault)
                    }
                }),
            )
        }
        runCatching { persistentModelCacheSaver(JsonHelper.encodeToString(payload)) }
    }

    private fun parseCachedRemoteModel(element: JsonElement): RemoteModel? {
        val item = element as? JsonObject ?: return null
        val id = stringField(item, "id") ?: return null
        val routes = (item["supportedRoutes"] as? JsonArray)
            ?.mapNotNull { route -> routeForStorageValue((route as? JsonPrimitive)?.contentOrNull) }
            ?.toSet()
            .orEmpty()
        return RemoteModel(
            id = id,
            supportedRoutes = routes,
            supportsFunctionCalling = boolField(item, "supportsFunctionCalling") ?: true,
            supportsVision = boolField(item, "supportsVision") ?: false,
            maxInputTokens = intField(item, "maxInputTokens"),
            maxOutputTokens = intField(item, "maxOutputTokens"),
            isDefault = boolField(item, "isDefault") ?: false,
        )
    }

    private fun mergeModels(fetched: List<RemoteModel>, cachedModels: List<RemoteModel>): List<RemoteModel> {
        val merged = LinkedHashMap<String, RemoteModel>()
        fetched.forEach { model -> merged[model.id] = model }
        cachedModels.forEach { model -> merged.putIfAbsent(model.id, model) }
        return merged.values.toList()
    }

    @OptIn(ExperimentalTime::class)
    private fun startMissingModelRetry(token: String, missingModels: List<RemoteModel>, firstFetched: List<RemoteModel>) {
        val missingIds = missingModels.mapTo(mutableSetOf()) { it.id }
        val retry = synchronized(missingModelRetryLock) {
            val active = missingModelRetry
            if (active != null && active.missingIds == missingIds) return
            val next = MissingModelRetry(missingIds, MODEL_RETRY_SEQUENCE.incrementAndGet())
            missingModelRetry = next
            next
        }
        Thread {
            retryMissingModels(token, retry, missingModels, firstFetched)
        }.apply {
            isDaemon = true
            name = "github-copilot-model-retry-${retry.sequence}"
            start()
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun retryMissingModels(
        token: String,
        retry: MissingModelRetry,
        missingModels: List<RemoteModel>,
        firstFetched: List<RemoteModel>,
    ) {
        val stillMissingIds = retry.missingIds.toMutableSet()
        val confirmedModels = LinkedHashMap<String, RemoteModel>()
        var successfulRetries = 0
        var latestFetched = firstFetched
        for (delay in missingModelRetryDelays) {
            if (!isActiveRetry(retry)) return
            Thread.sleep(delay.toMillis())
            if (!isActiveRetry(retry)) return
            val fetched = runCatching { fetchModels(token) }.getOrDefault(emptyList())
            if (fetched.isEmpty()) continue
            successfulRetries++
            latestFetched = fetched
            fetched.forEach { model ->
                if (model.id in stillMissingIds) {
                    stillMissingIds.remove(model.id)
                    confirmedModels[model.id] = model
                }
            }
            if (stillMissingIds.isEmpty()) {
                cacheModels(mergeModels(fetched, confirmedModels.values.toList()))
                finishMissingModelRetry(retry)
                return
            }
        }
        if (isActiveRetry(retry)) {
            val cachedConfirmedModels = missingModels.filter { it.id !in stillMissingIds && it.id !in confirmedModels }
            val unconfirmedMissingModels = if (successfulRetries < missingModelRetryDelays.size) {
                missingModels.filter { it.id in stillMissingIds }
            } else {
                emptyList()
            }
            cacheModels(
                mergeModels(latestFetched, confirmedModels.values.toList() + cachedConfirmedModels + unconfirmedMissingModels),
            )
            finishMissingModelRetry(retry)
        }
    }

    private fun isActiveRetry(retry: MissingModelRetry): Boolean = missingModelRetry === retry

    private fun clearMissingModelRetry() {
        synchronized(missingModelRetryLock) {
            missingModelRetry = null
        }
    }

    private fun finishMissingModelRetry(retry: MissingModelRetry) {
        synchronized(missingModelRetryLock) {
            if (missingModelRetry === retry) missingModelRetry = null
        }
    }

    private fun fetchModels(token: String): List<RemoteModel> {
        val request =
            HttpRequest.newBuilder(URI.create(UrlResolver.resolveTargetUrl("/models", upstreamBaseUri.toString())))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .header("Copilot-Integration-Id", COPILOT_INTEGRATION_ID)
                .header("Editor-Version", EDITOR_VERSION)
                .header("Editor-Plugin-Version", EDITOR_PLUGIN_VERSION)
                .header("X-GitHub-Api-Version", API_VERSION)
                .GET()
                .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) return emptyList()
        val root = JsonHelper.parseToJsonElementOrNull(response.body()) ?: return emptyList()
        val data = when (root) {
            is JsonObject -> root["data"] as? JsonArray ?: root["models"] as? JsonArray
            is JsonArray -> root
            else -> null
        } ?: return emptyList()
        return data.mapNotNull { parseRemoteModel(it) }.filter { it.supportedRoutes.isNotEmpty() }.distinctBy { it.id }
    }

    private fun parseRemoteModel(element: JsonElement): RemoteModel? {
        val item = element as? JsonObject ?: return null
        if (boolField(item, "model_picker_enabled") == false) return null
        if (stringField(item.jsonObject("policy"), "state") == "disabled") return null
        val capabilities = item.jsonObject("capabilities")
        val limits = capabilities?.jsonObject("limits")
        val supports = capabilities?.jsonObject("supports")
        val toolCalls = boolField(supports, "tool_calls") ?: true
        val id = remoteModelId(stringField(item, "id") ?: return null) ?: return null
        return RemoteModel(
            id = id,
            supportedRoutes = supportedRoutes(id, modelType(item), item["supported_endpoints"] as? JsonArray),
            supportsFunctionCalling = toolCalls,
            supportsVision = supportsVision(capabilities, supports),
            maxInputTokens = intField(limits, "max_context_window_tokens") ?: intField(limits, "max_prompt_tokens"),
            maxOutputTokens = intField(limits, "max_output_tokens"),
            isDefault = boolField(item, "is_default") ?: boolField(item, "default") ?: false,
        )
    }

    private fun supportedRoutes(
        modelId: String,
        modelType: String?,
        endpoints: JsonArray?
    ): Set<SubscriptionProxyRoute> {
        val values = endpoints?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty()
        if (values.isEmpty()) {
            return if (modelType == "embeddings") {
                emptySet()
            } else {
                buildSet {
                    if (shouldUseResponsesApi(modelId)) add(SubscriptionProxyRoute.RESPONSES)
                    add(SubscriptionProxyRoute.CHAT_COMPLETIONS)
                }
            }
        }
        return buildSet {
            val hasChat = values.any { it.endsWith("/chat/completions") || it == "/chat/completions" }
            val hasResponses = values.any { it.endsWith("/responses") || it == "/responses" }
            val hasMessages = values.any { it == "/v1/messages" || it == "/messages" }
            if (hasMessages) {
                add(SubscriptionProxyRoute.ANTHROPIC_MESSAGES)
                return@buildSet
            }
            if (hasChat) {
                add(SubscriptionProxyRoute.CHAT_COMPLETIONS)
            }
            if (hasResponses) {
                add(SubscriptionProxyRoute.RESPONSES)
            }
            if (isEmpty()) add(SubscriptionProxyRoute.CHAT_COMPLETIONS)
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
        return if (shouldBridgeChatToMessages(request)) {
            SubscriptionProxyRoute.ANTHROPIC_MESSAGES
        } else {
            request.route
        }
    }

    private fun requestBody(request: SubscriptionProxyRequest, body: JsonObject): JsonObject {
        if (shouldBridgeChatToMessages(request)) {
            return openAiChatToAnthropicMessagesBody(request, body)
        }
        if (request.route == SubscriptionProxyRoute.RESPONSES && request.model.upstreamId.startsWith("gpt-")) {
            return body.remove("max_output_tokens")
        }
        if (request.route != SubscriptionProxyRoute.ANTHROPIC_MESSAGES) return body
        return buildJsonObject {
            body.forEach { (key, value) ->
                if (key !in UNSUPPORTED_MESSAGES_BODY_FIELDS) put(key, value)
            }
        }
    }

    private fun shouldBridgeChatToMessages(request: SubscriptionProxyRequest): Boolean {
        return request.route == SubscriptionProxyRoute.CHAT_COMPLETIONS &&
            SubscriptionProxyRoute.ANTHROPIC_MESSAGES in request.model.supportedRoutes &&
            isClaudeModel(request.model.upstreamId)
    }

    private fun openAiChatToAnthropicMessagesBody(request: SubscriptionProxyRequest, body: JsonObject): JsonObject {
        val messages = body["messages"] as? JsonArray ?: JsonArray(emptyList())
        val systemParts = messages.mapNotNull { message ->
            val item = message as? JsonObject ?: return@mapNotNull null
            val role = stringField(item, "role") ?: return@mapNotNull null
            if (role == "system" || role == "developer") contentText(item["content"]) else null
        }.filter { it.isNotBlank() }.toMutableList()
        openAiResponseFormatInstruction(body["response_format"] as? JsonObject)?.let { systemParts.add(it) }
        val systemText = systemParts.joinToString("\n\n")
        val anthropicMessages = buildJsonArray {
            messages.forEach { message ->
                val item = message as? JsonObject ?: return@forEach
                val role = stringField(item, "role") ?: return@forEach
                if (role == "system" || role == "developer") return@forEach
                val mappedRole = if (role == "assistant") "assistant" else "user"
                add(buildJsonObject {
                    put("role", mappedRole)
                    put("content", openAiMessageContentToAnthropicContent(item, role))
                })
            }
        }
        return buildJsonObject {
            put("model", request.model.upstreamId)
            put("max_tokens", anthropicMaxTokens(request, body))
            if (systemText.isNotBlank()) put("system", systemText)
            put("messages", anthropicMessages)
            body["stream"]?.let { put("stream", it) }
            body["temperature"]?.let { put("temperature", it) }
            body["top_p"]?.let { put("top_p", it) }
            body["stop"]?.let { put("stop_sequences", it) }
            val toolChoice = body["tool_choice"]
            if (!isOpenAiToolChoiceNone(toolChoice)) {
                val tools = body["tools"] as? JsonArray ?: openAiFunctionsToTools(body["functions"] as? JsonArray)
                openAiToolsToAnthropicTools(tools)?.let { put("tools", it) }
                openAiToolChoiceToAnthropicToolChoice(toolChoice ?: openAiFunctionCallToToolChoice(body["function_call"]))
                    ?.let { put("tool_choice", it) }
            }
        }
    }

    private fun openAiResponseFormatInstruction(responseFormat: JsonObject?): String? {
        responseFormat ?: return null
        return when (stringField(responseFormat, "type")) {
            "json_object" -> "Respond with a valid JSON object only. Do not wrap it in Markdown fences."
            "json_schema" -> {
                val schema = responseFormat["json_schema"]?.let(JsonHelper::encodeToString).orEmpty()
                "Respond with a valid JSON object only. Do not wrap it in Markdown fences. Follow this JSON schema when possible: $schema"
            }

            else -> null
        }
    }

    private fun openAiFunctionsToTools(functions: JsonArray?): JsonArray? {
        functions ?: return null
        return buildJsonArray {
            functions.forEach { function ->
                val item = function as? JsonObject ?: return@forEach
                add(buildJsonObject {
                    put("type", "function")
                    put("function", item)
                })
            }
        }.takeIf { it.isNotEmpty() }
    }

    private fun openAiFunctionCallToToolChoice(functionCall: JsonElement?): JsonElement? {
        return when (functionCall) {
            is JsonPrimitive -> when (functionCall.contentOrNull) {
                "auto" -> JsonPrimitive("auto")
                "none" -> JsonPrimitive("none")
                else -> null
            }

            is JsonObject -> buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    stringField(functionCall, "name")?.let { put("name", it) }
                })
            }

            else -> null
        }
    }

    private fun openAiMessageContentToAnthropicContent(message: JsonObject, role: String): JsonElement {
        if (role == "tool") {
            return buildJsonArray {
                add(buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", stringField(message, "tool_call_id").orEmpty())
                    put("content", contentText(message["content"]))
                })
            }
        }
        val toolCalls = message["tool_calls"] as? JsonArray
        if (role != "assistant" || toolCalls == null || toolCalls.isEmpty()) {
            return openAiContentToAnthropicContent(message["content"])
        }
        return buildJsonArray {
            val text = contentText(message["content"])
            if (text.isNotBlank()) {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            }
            toolCalls.forEach { toolCall ->
                val item = toolCall as? JsonObject ?: return@forEach
                val function = item["function"] as? JsonObject ?: return@forEach
                val name = stringField(function, "name") ?: return@forEach
                add(buildJsonObject {
                    put("type", "tool_use")
                    put("id", stringField(item, "id").orEmpty())
                    put("name", name)
                    put("input", parseToolArguments(stringField(function, "arguments")))
                })
            }
        }
    }

    private fun openAiToolsToAnthropicTools(tools: JsonArray?): JsonArray? {
        tools ?: return null
        return buildJsonArray {
            tools.forEach { tool ->
                val item = tool as? JsonObject ?: return@forEach
                val function = item["function"] as? JsonObject ?: return@forEach
                val name = stringField(function, "name") ?: return@forEach
                add(buildJsonObject {
                    put("name", name)
                    stringField(function, "description")?.let { put("description", it) }
                    put("input_schema", function["parameters"] ?: buildJsonObject { put("type", "object") })
                })
            }
        }.takeIf { it.isNotEmpty() }
    }

    private fun openAiToolChoiceToAnthropicToolChoice(toolChoice: JsonElement?): JsonObject? {
        return when (toolChoice) {
            is JsonPrimitive -> when (toolChoice.contentOrNull) {
                "auto" -> buildJsonObject { put("type", "auto") }
                "required" -> buildJsonObject { put("type", "any") }
                "none", null -> null
                else -> null
            }

            is JsonObject -> {
                val functionName = stringField(toolChoice["function"] as? JsonObject, "name") ?: return null
                buildJsonObject {
                    put("type", "tool")
                    put("name", functionName)
                }
            }

            else -> null
        }
    }

    private fun isOpenAiToolChoiceNone(toolChoice: JsonElement?): Boolean {
        return toolChoice is JsonPrimitive && toolChoice.contentOrNull == "none"
    }

    private fun parseToolArguments(arguments: String?): JsonElement {
        if (arguments.isNullOrBlank()) return buildJsonObject { }
        return JsonHelper.parseToJsonElementOrNull(arguments) ?: JsonPrimitive(arguments)
    }

    private fun openAiContentToAnthropicContent(content: JsonElement?): JsonElement {
        return when (content) {
            is JsonArray -> buildJsonArray {
                content.forEach { block ->
                    add(openAiContentBlockToAnthropic(block))
                }
            }

            JsonNull, null -> JsonPrimitive("")
            else -> JsonPrimitive(contentText(content))
        }
    }

    private fun openAiContentBlockToAnthropic(block: JsonElement): JsonElement {
        val item = block as? JsonObject ?: return block
        return when (stringField(item, "type")) {
            "text" -> buildJsonObject {
                put("type", "text")
                put("text", stringField(item, "text").orEmpty())
            }

            "image_url" -> openAiImageUrlToAnthropicImage(item) ?: item
            else -> item
        }
    }

    private fun openAiImageUrlToAnthropicImage(item: JsonObject): JsonObject? {
        val url = stringField(item["image_url"] as? JsonObject, "url") ?: return null
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return fetchRemoteImageUrlToAnthropicImage(url)
        }
        val dataPrefix = "data:"
        if (!url.startsWith(dataPrefix)) return null
        val mediaType = url.substringAfter(dataPrefix).substringBefore(';').takeIf { it.isNotBlank() } ?: return null
        val data = url.substringAfter("base64,", missingDelimiterValue = "").takeIf { it.isNotBlank() } ?: return null
        return buildJsonObject {
            put("type", "image")
            put("source", buildJsonObject {
                put("type", "base64")
                put("media_type", mediaType)
                put("data", data)
            })
        }
    }

    private fun fetchRemoteImageUrlToAnthropicImage(url: String): JsonObject? {
        val response = try {
            remoteImageHttpClient.send(
                HttpRequest.newBuilder(URI.create(url))
                    .timeout(REMOTE_IMAGE_TIMEOUT)
                    .header("Accept", "image/*")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
        } catch (_: Exception) {
            return null
        }
        if (response.statusCode() !in 200..<300) return null
        val bytes = response.body()
        if (bytes.isEmpty() || bytes.size > MAX_REMOTE_IMAGE_BYTES) return null
        val mediaType = response.headers().firstValue("Content-Type").orElse("")
            .substringBefore(';')
            .trim()
            .takeIf { it.startsWith("image/") }
            ?: mediaTypeFromImageUrl(url)
            ?: return null
        return buildJsonObject {
            put("type", "image")
            put("source", buildJsonObject {
                put("type", "base64")
                put("media_type", mediaType)
                put("data", Base64.getEncoder().encodeToString(bytes))
            })
        }
    }

    private fun mediaTypeFromImageUrl(url: String): String? {
        val path = URI.create(url).path.lowercase()
        return when {
            path.endsWith(".png") -> "image/png"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".gif") -> "image/gif"
            path.endsWith(".webp") -> "image/webp"
            else -> null
        }
    }

    private fun contentText(content: JsonElement?): String {
        return when (content) {
            is JsonPrimitive -> content.contentOrNull.orEmpty()
            is JsonArray -> content.joinToString("") { block ->
                ((block as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty()
            }

            else -> ""
        }
    }

    private fun anthropicMaxTokens(request: SubscriptionProxyRequest, body: JsonObject): Int {
        return intField(body, "max_tokens")
            ?: intField(body, "max_completion_tokens")
            ?: request.model.maxOutputTokens?.coerceAtMost(DEFAULT_ANTHROPIC_MAX_TOKENS)
            ?: DEFAULT_ANTHROPIC_MAX_TOKENS
    }

    private fun openAiChatJsonResponse(request: SubscriptionProxyRequest, body: String): String {
        if (shouldBridgeChatToMessages(request)) return anthropicMessageToOpenAiChat(request, body)
        return openAiChatEnvelope(request, body, "chat.completion")
    }

    private fun openAiChatSseData(request: SubscriptionProxyRequest, data: String): String {
        if (shouldBridgeChatToMessages(request)) return anthropicSseDataToOpenAiChat(request, data)
        return openAiChatEnvelope(request, data, "chat.completion.chunk")
    }

    private fun openAiChatSseLine(request: SubscriptionProxyRequest, line: String): String? {
        if (!shouldBridgeChatToMessages(request)) return null
        if (line.startsWith("event:")) return ""
        if (!line.startsWith("data:")) return line
        val rawData = line.substringAfter("data:")
        val leadingWhitespace = rawData.takeWhile { it == ' ' || it == '\t' }
        val data = rawData.drop(leadingWhitespace.length)
        if (data == "[DONE]") {
            streamToolCallIndexes.remove(request.requestId)
            return line
        }
        val transformed = anthropicSseDataToOpenAiChat(request, data)
        if (transformed.isEmpty()) return ""
        return "data:$leadingWhitespace$transformed"
    }

    private fun anthropicMessageToOpenAiChat(request: SubscriptionProxyRequest, body: String): String {
        val root = JsonHelper.parseToJsonElementOrNull(body) as? JsonObject ?: return body
        if (root.containsKey("error")) return body
        val content = root["content"] as? JsonArray
        val text = anthropicText(content)
        val toolCalls = anthropicToolCalls(content)
        val legacyFunctionCall = legacyOpenAiFunctionCall(request, toolCalls)
        val finishReason = if (legacyFunctionCall != null) {
            "function_call"
        } else {
            openAiFinishReason(stringField(root, "stop_reason"))
        }
        return JsonHelper.encodeToString(buildJsonObject {
            put("id", root["id"] ?: JsonPrimitive("chatcmpl-${request.requestId}"))
            put("object", "chat.completion")
            put("created", System.currentTimeMillis() / 1000L)
            put("model", request.model.localId)
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", 0)
                    put("message", buildJsonObject {
                        put("role", "assistant")
                        if (legacyFunctionCall != null) {
                            put("content", JsonNull)
                            put("function_call", legacyFunctionCall)
                        } else {
                            put("content", text)
                            if (toolCalls != null) put("tool_calls", toolCalls)
                        }
                    })
                    put("finish_reason", finishReason)
                })
            })
            anthropicUsageToOpenAi(root["usage"] as? JsonObject)?.let { put("usage", it) }
        })
    }

    private fun legacyOpenAiFunctionCall(request: SubscriptionProxyRequest, toolCalls: JsonArray?): JsonObject? {
        if (request.body["functions"] !is JsonArray || request.body["tools"] is JsonArray) return null
        val toolCall = toolCalls?.firstOrNull() as? JsonObject ?: return null
        return toolCall["function"] as? JsonObject
    }

    private fun anthropicSseDataToOpenAiChat(request: SubscriptionProxyRequest, data: String): String {
        val root = JsonHelper.parseToJsonElementOrNull(data) as? JsonObject ?: return data
        return when (stringField(root, "type")) {
            "message_start" -> openAiChatChunk(request, role = "assistant")
            "content_block_start" -> {
                val block = root["content_block"] as? JsonObject
                if (block != null && stringField(block, "type") == "tool_use") {
                    openAiToolCallStartChunk(request, root, block)
                } else {
                    openAiChatChunk(request)
                }
            }

            "content_block_delta" -> {
                val delta = root["delta"] as? JsonObject
                val text = stringField(delta, "text").orEmpty()
                val partialJson = stringField(delta, "partial_json").orEmpty()
                when {
                    text.isNotEmpty() -> openAiChatChunk(request, content = text)
                    partialJson.isNotEmpty() -> openAiToolCallArgumentsChunk(request, root, partialJson)
                    else -> openAiChatChunk(request)
                }
            }

            "message_delta" -> {
                val delta = root["delta"] as? JsonObject
                val finishChunk = openAiChatChunk(request, finishReason = openAiFinishReason(stringField(delta, "stop_reason")))
                val usageChunk = if (openAiIncludeUsage(request)) openAiUsageChunk(request, root["usage"] as? JsonObject) else null
                if (usageChunk == null) finishChunk else "$finishChunk\n\ndata: $usageChunk"
            }

            "message_stop" -> ""
            else -> openAiChatChunk(request)
        }
    }

    private fun streamToolCallIndex(request: SubscriptionProxyRequest, blockIndex: Int): Int {
        synchronized(streamToolCallIndexes) {
            val indexes = streamToolCallIndexes.getOrPut(request.requestId) { LinkedHashMap() }
            return indexes.getOrPut(blockIndex) { indexes.size }
        }
    }

    private fun openAiToolCallStartChunk(request: SubscriptionProxyRequest, root: JsonObject, block: JsonObject): String {
        val toolCallIndex = streamToolCallIndex(request, intField(root, "index") ?: 0)
        return JsonHelper.encodeToString(buildJsonObject {
            put("id", "chatcmpl-${request.requestId}")
            put("object", "chat.completion.chunk")
            put("created", System.currentTimeMillis() / 1000L)
            put("model", request.model.localId)
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", 0)
                    put("delta", buildJsonObject {
                        put("tool_calls", buildJsonArray {
                            add(buildJsonObject {
                                put("index", toolCallIndex)
                                put("id", stringField(block, "id").orEmpty())
                                put("type", "function")
                                put("function", buildJsonObject {
                                    put("name", stringField(block, "name").orEmpty())
                                    put("arguments", "")
                                })
                            })
                        })
                    })
                    put("finish_reason", JsonNull)
                })
            })
        })
    }

    private fun openAiToolCallArgumentsChunk(request: SubscriptionProxyRequest, root: JsonObject, partialJson: String): String {
        val toolCallIndex = streamToolCallIndex(request, intField(root, "index") ?: 0)
        return JsonHelper.encodeToString(buildJsonObject {
            put("id", "chatcmpl-${request.requestId}")
            put("object", "chat.completion.chunk")
            put("created", System.currentTimeMillis() / 1000L)
            put("model", request.model.localId)
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", 0)
                    put("delta", buildJsonObject {
                        put("tool_calls", buildJsonArray {
                            add(buildJsonObject {
                                put("index", toolCallIndex)
                                put("function", buildJsonObject {
                                    put("arguments", partialJson)
                                })
                            })
                        })
                    })
                    put("finish_reason", JsonNull)
                })
            })
        })
    }

    private fun openAiChatChunk(
        request: SubscriptionProxyRequest,
        role: String? = null,
        content: String? = null,
        finishReason: String? = null,
    ): String {
        return JsonHelper.encodeToString(buildJsonObject {
            put("id", "chatcmpl-${request.requestId}")
            put("object", "chat.completion.chunk")
            put("created", System.currentTimeMillis() / 1000L)
            put("model", request.model.localId)
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", 0)
                    put("delta", buildJsonObject {
                        role?.let { put("role", it) }
                        content?.let { put("content", it) }
                    })
                    if (finishReason == null) put("finish_reason", JsonNull) else put("finish_reason", finishReason)
                })
            })
        })
    }

    private fun openAiUsageChunk(request: SubscriptionProxyRequest, usage: JsonObject?): String? {
        val normalizedUsage = anthropicUsageToOpenAi(usage) ?: return null
        return JsonHelper.encodeToString(buildJsonObject {
            put("id", "chatcmpl-${request.requestId}")
            put("object", "chat.completion.chunk")
            put("created", System.currentTimeMillis() / 1000L)
            put("model", request.model.localId)
            put("choices", JsonArray(emptyList()))
            put("usage", normalizedUsage)
        })
    }

    private fun openAiIncludeUsage(request: SubscriptionProxyRequest): Boolean {
        val streamOptions = request.body["stream_options"] as? JsonObject ?: return false
        return (streamOptions["include_usage"] as? JsonPrimitive)?.booleanOrNull == true
    }

    private fun anthropicText(content: JsonArray?): String {
        return content.orEmpty().joinToString("") { block ->
            val item = block as? JsonObject ?: return@joinToString ""
            if (stringField(item, "type") == "text") stringField(item, "text").orEmpty() else ""
        }
    }

    private fun anthropicToolCalls(content: JsonArray?): JsonArray? {
        content ?: return null
        return buildJsonArray {
            content.forEach { block ->
                val item = block as? JsonObject ?: return@forEach
                if (stringField(item, "type") != "tool_use") return@forEach
                val id = stringField(item, "id") ?: return@forEach
                val name = stringField(item, "name") ?: return@forEach
                add(buildJsonObject {
                    put("id", id)
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", name)
                        put("arguments", JsonHelper.encodeToString(item["input"] ?: buildJsonObject { }))
                    })
                })
            }
        }.takeIf { it.isNotEmpty() }
    }

    private fun anthropicUsageToOpenAi(usage: JsonObject?): JsonObject? {
        usage ?: return null
        val input = intField(usage, "input_tokens") ?: 0
        val output = intField(usage, "output_tokens") ?: 0
        return buildJsonObject {
            put("prompt_tokens", input)
            put("completion_tokens", output)
            put("total_tokens", input + output)
        }
    }

    private fun openAiFinishReason(reason: String?): String {
        return when (reason) {
            "end_turn", "stop_sequence", null -> "stop"
            "max_tokens" -> "length"
            "tool_use" -> "tool_calls"
            else -> reason
        }
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
                if (key !in OPENAI_CHAT_ENVELOPE_FIELDS && value != JsonNull) put(key, value)
            }
        }
        return JsonHelper.encodeToString(normalized)
    }

    private fun responsesChatBody(body: MutableJsonObject) {
        body.remove("store")
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
            "User-Agent" to USER_AGENT,
            "Copilot-Integration-Id" to COPILOT_INTEGRATION_ID,
            "Editor-Version" to EDITOR_VERSION,
            "Editor-Plugin-Version" to EDITOR_PLUGIN_VERSION,
            "X-GitHub-Api-Version" to API_VERSION,
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

    private data class RemoteModel(
        val id: String,
        val supportedRoutes: Set<SubscriptionProxyRoute>,
        val supportsFunctionCalling: Boolean,
        val supportsVision: Boolean,
        val maxInputTokens: Int?,
        val maxOutputTokens: Int?,
        val isDefault: Boolean,
    )

    private data class ModelCache(
        val models: List<RemoteModel>,
        val fetchedAt: Instant,
    )

    private data class MissingModelRetry(
        val missingIds: Set<String>,
        val sequence: Long,
    )

    companion object {
        const val ID = "github"
        const val PREFIX = "gh-"
        private const val OPENCODE_PROVIDER_PREFIX = "github-copilot/"
        private const val DISPLAY_NAME = "GitHub Copilot"
        private const val DEFAULT_RESPONSES_INSTRUCTIONS = "You are a coding assistant."
        private const val LITELLM_PROVIDER = "github_copilot"
        private const val USER_AGENT = "GitHubCopilotChat/0.26.7"
        private const val COPILOT_INTEGRATION_ID = "vscode-chat"
        private const val EDITOR_VERSION = "vscode/1.104.1"
        private const val EDITOR_PLUGIN_VERSION = "copilot-chat/0.26.7"
        private const val API_VERSION = "2026-06-01"
        private const val DEFAULT_ANTHROPIC_MAX_TOKENS = 4096
        private const val MAX_REMOTE_IMAGE_BYTES = 5 * 1024 * 1024
        private val REMOTE_IMAGE_TIMEOUT = Duration.ofSeconds(15)
        private val OPENAI_CHAT_ENVELOPE_FIELDS = setOf("id", "object", "created", "model", "choices")
        private val UNSUPPORTED_MESSAGES_BODY_FIELDS = setOf("context_management", "output_config", "thinking")
        val DEFAULT_UPSTREAM_BASE_URI: URI = URI.create("https://api.githubcopilot.com")
        private val DEFAULT_CACHE_TTL = 5.minutes
        private val DEFAULT_MISSING_MODEL_RETRY_DELAYS: List<Duration> = List(10) { index ->
            Duration.ofMillis(1_000L shl index)
        }
        private val DEFAULT_REQUEST_LOG_DIR = System.getProperty("java.io.tmpdir") +
                "/openai-usage-quota-intellij/subscription-proxy-github-requests"
        private val MODEL_RETRY_SEQUENCE = AtomicLong()

        private fun String?.trimmedOrNull(): String? = this?.trim()?.takeIf { it.isNotBlank() }

        private fun intField(item: JsonObject?, name: String): Int? {
            return (item?.get(name) as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        }

        private fun boolField(item: JsonObject?, name: String): Boolean? {
            return (item?.get(name) as? JsonPrimitive)?.booleanOrNull
        }

        private fun stringField(item: JsonObject?, name: String): String? {
            return (item?.get(name) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }

        private fun JsonObject.jsonObject(name: String): JsonObject? {
            return this[name] as? JsonObject
        }

        private fun modelType(item: JsonObject): String? {
            val capabilities = item["capabilities"] as? JsonObject ?: return null
            return (capabilities["type"] as? JsonPrimitive)?.contentOrNull
        }

        private fun remoteModelId(rawId: String): String? {
            return rawId.trim()
                .removePrefix(OPENCODE_PROVIDER_PREFIX)
                .takeIf { it.isNotBlank() }
        }

        private fun fallbackUpstreamId(localId: String): String? {
            val trimmed = localId.trim()
            val upstreamId = when {
                trimmed.startsWith(PREFIX) -> trimmed.removePrefix(PREFIX)
                trimmed.startsWith(OPENCODE_PROVIDER_PREFIX) -> trimmed.removePrefix(OPENCODE_PROVIDER_PREFIX)
                else -> return null
            }
            return upstreamId.takeIf { it.isNotBlank() }
        }

        private fun supportsVision(capabilities: JsonObject?, supports: JsonObject?): Boolean {
            if (boolField(supports, "vision") == true) return true
            val vision = capabilities?.jsonObject("limits")?.jsonObject("vision") ?: return false
            val mediaTypes = vision["supported_media_types"] as? JsonArray ?: return false
            return mediaTypes.any { (it as? JsonPrimitive)?.contentOrNull?.startsWith("image/") == true }
        }

        private fun routeForStorageValue(value: String?): SubscriptionProxyRoute? {
            return SubscriptionProxyRoute.entries.firstOrNull { route ->
                value == route.normalizedPath || value == route.upstreamPath || value == "/v1${route.normalizedPath}"
            }
        }

        private fun shouldUseResponsesApi(modelId: String): Boolean {
            if (modelId.startsWith("mai-code-")) {
                return true
            }
            val major = GPT_MAJOR_REGEX.find(modelId)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return false
            return major >= 5 && !modelId.startsWith("gpt-5-mini")
        }

        private fun shouldBridgeResponsesModel(modelId: String): Boolean = shouldUseResponsesApi(modelId)

        private fun isClaudeModel(modelId: String): Boolean = modelId.startsWith("claude-")

        private val GPT_MAJOR_REGEX = Regex("^gpt-(\\d+)")

        private fun containsImageInput(element: JsonElement?): Boolean {
            return when (element) {
                is JsonObject -> {
                    val type = (element["type"] as? JsonPrimitive)?.contentOrNull
                    type == "image_url" || type == "input_image" || element.values.any(::containsImageInput)
                }

                is JsonArray -> element.any(::containsImageInput)
                else -> false
            }
        }
    }
}
