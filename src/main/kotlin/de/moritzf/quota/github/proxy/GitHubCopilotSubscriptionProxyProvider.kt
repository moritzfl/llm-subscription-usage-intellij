package de.moritzf.quota.github.proxy

import de.moritzf.proxy.logging.RequestLogger
import de.moritzf.proxy.server.JsonHelper
import de.moritzf.proxy.server.remove
import de.moritzf.proxy.subscription.PassThroughSubscriptionProxyProvider
import de.moritzf.proxy.subscription.SubscriptionProxyModel
import de.moritzf.proxy.subscription.SubscriptionProxyProvider
import de.moritzf.proxy.subscription.SubscriptionProxyRequest
import de.moritzf.proxy.subscription.SubscriptionProxyRoute
import de.moritzf.proxy.transport.UrlResolver
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    fullRequestLogging: Boolean = false,
    requestLogDir: String = DEFAULT_REQUEST_LOG_DIR,
) : SubscriptionProxyProvider {
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
        jsonResponseTransformer = ::openAiChatJsonResponse,
        sseDataTransformer = ::openAiChatSseData,
        httpClient = httpClient,
        requestLogger = RequestLogger(fullRequestLogging, Path.of(requestLogDir)),
    )

    @Volatile
    private var modelCache: ModelCache? = null

    override val id: String = ID
    override val displayName: String = DISPLAY_NAME

    override fun isConfigured(): Boolean = delegate.isConfigured()

    override fun models() = delegate.models()

    override fun fallbackModel(localId: String, route: SubscriptionProxyRoute) = prefixedFallbackModel(localId, route)

    override suspend fun handle(ctx: de.moritzf.proxy.server.ProxyCall, request: SubscriptionProxyRequest) {
        delegate.handle(ctx, request)
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
                supportedRoutes = model.supportedRoutes,
                supportsFunctionCalling = model.supportsFunctionCalling,
                supportsToolChoice = true,
                supportsVision = model.supportsVision,
                maxInputTokens = model.maxInputTokens,
                maxOutputTokens = model.maxOutputTokens,
                isDefault = model.isDefault,
            )
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun remoteModels(): List<RemoteModel> {
        val now = Clock.System.now()
        val cached = modelCache
        if (cached != null && now - cached.fetchedAt < CACHE_TTL) {
            return cached.models
        }
        val token = accessTokenProvider().trimmedOrNull() ?: return emptyList()
        val models = runCatching { fetchModels(token) }.getOrDefault(emptyList())
        if (models.isNotEmpty()) {
            modelCache = ModelCache(models, now)
        }
        return models
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
        if (request.route != SubscriptionProxyRoute.ANTHROPIC_MESSAGES) return headers
        return headers.filterKeys { !it.equals("anthropic-beta", ignoreCase = true) }
    }

    private fun requestBody(request: SubscriptionProxyRequest, body: JsonObject): JsonObject {
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

    private fun openAiChatJsonResponse(request: SubscriptionProxyRequest, body: String): String {
        return openAiChatEnvelope(request, body, "chat.completion")
    }

    private fun openAiChatSseData(request: SubscriptionProxyRequest, data: String): String {
        return openAiChatEnvelope(request, data, "chat.completion.chunk")
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

    companion object {
        const val ID = "github"
        const val PREFIX = "gh-"
        private const val OPENCODE_PROVIDER_PREFIX = "github-copilot/"
        private const val DISPLAY_NAME = "GitHub Copilot"
        private const val LITELLM_PROVIDER = "github_copilot"
        private const val USER_AGENT = "GitHubCopilotChat/0.26.7"
        private const val COPILOT_INTEGRATION_ID = "vscode-chat"
        private const val EDITOR_VERSION = "vscode/1.104.1"
        private const val EDITOR_PLUGIN_VERSION = "copilot-chat/0.26.7"
        private const val API_VERSION = "2026-06-01"
        private val OPENAI_CHAT_ENVELOPE_FIELDS = setOf("id", "object", "created", "model", "choices")
        private val UNSUPPORTED_MESSAGES_BODY_FIELDS = setOf("context_management", "output_config", "thinking")
        val DEFAULT_UPSTREAM_BASE_URI: URI = URI.create("https://api.githubcopilot.com")
        private val CACHE_TTL = 5.minutes
        private val DEFAULT_REQUEST_LOG_DIR = System.getProperty("java.io.tmpdir") +
                "/openai-usage-quota-intellij/subscription-proxy-github-requests"

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

        private fun shouldUseResponsesApi(modelId: String): Boolean {
            val major = GPT_MAJOR_REGEX.find(modelId)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return false
            return major >= 5 && !modelId.startsWith("gpt-5-mini")
        }

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
