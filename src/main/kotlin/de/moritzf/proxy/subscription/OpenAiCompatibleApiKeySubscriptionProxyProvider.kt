package de.moritzf.proxy.subscription

import de.moritzf.proxy.logging.RequestLogger
import de.moritzf.proxy.server.JsonHelper
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

class OpenAiCompatibleApiKeySubscriptionProxyProvider(
    override val id: String,
    override val displayName: String,
    private val litellmProvider: String,
    private val baseUri: URI,
    private val apiKeyProvider: () -> String?,
    private val localIdPrefix: String = "",
    private val staticModels: List<StaticModel> = emptyList(),
    private val discoverModels: Boolean = true,
    private val supportedRoutes: Set<SubscriptionProxyRoute> = setOf(SubscriptionProxyRoute.CHAT_COMPLETIONS),
    private val nativeCompletionsRoute: SubscriptionProxyRoute = SubscriptionProxyRoute.COMPLETIONS,
    private val upstreamRouteProvider: (SubscriptionProxyRequest) -> SubscriptionProxyRoute = { it.route },
    private val upstreamUrlProvider: (SubscriptionProxyRequest) -> String? = { null },
    private val requestBodyTransformer: (SubscriptionProxyRequest, JsonObject) -> JsonObject = { _, body -> body },
    private val jsonResponseTransformer: ((SubscriptionProxyRequest, String) -> String)? = null,
    private val modelTransformer: (StaticModel) -> StaticModel = { it },
    private val includeModel: (String) -> Boolean = { true },
    private val extraRoutesForModel: (String) -> Set<SubscriptionProxyRoute> = { emptySet() },
    private val defaultHeaders: Map<String, String> = DEFAULT_HEADERS,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build(),
    fullRequestLogging: Boolean = false,
    requestLogDir: String = DEFAULT_REQUEST_LOG_DIR,
    private val modelCacheTtl: kotlin.time.Duration = CACHE_TTL,
) : SubscriptionProxyProvider {
    private val delegate = PassThroughSubscriptionProxyProvider(
        id = id,
        displayName = displayName,
        litellmProvider = litellmProvider,
        baseUri = baseUri,
        accessTokenProvider = apiKeyProvider,
        modelMappingsProvider = ::modelMappings,
        defaultHeaders = defaultHeaders,
        upstreamRouteProvider = { request ->
            val routed =
                if (request.route == SubscriptionProxyRoute.COMPLETIONS) {
                    request.copy(route = nativeCompletionsRoute)
                } else {
                    request
                }
            upstreamRouteProvider(routed)
        },
        upstreamUrlProvider = upstreamUrlProvider,
        requestBodyTransformer = requestBodyTransformer,
        jsonResponseTransformer = jsonResponseTransformer,
        httpClient = httpClient,
        requestLogger = RequestLogger(fullRequestLogging, Path.of(requestLogDir)),
    )

    @Volatile private var modelCache: ModelCache? = null

    override fun isConfigured(): Boolean = delegate.isConfigured()

    override fun models(): List<SubscriptionProxyModel> = delegate.models()

    override fun fallbackModel(localId: String, route: SubscriptionProxyRoute): SubscriptionProxyModel? {
        if (localIdPrefix.isBlank() ||
            !localId.startsWith(localIdPrefix) ||
            localId.length == localIdPrefix.length
        ) {
            return null
        }
        val upstreamId = localId.removePrefix(localIdPrefix)
        if (!includeModel(upstreamId)) return null
        val routes = routesFor(upstreamId)
        if (route !in routes) return null
        return SubscriptionProxyModel(
            localId = localId,
            upstreamId = upstreamId,
            providerId = id,
            providerName = displayName,
            litellmProvider = litellmProvider,
            supportedRoutes = setOf(route),
            supportsFunctionCalling = true,
            supportsToolChoice = true,
            supportsVision = true,
        )
    }

    override suspend fun handle(ctx: de.moritzf.proxy.server.ProxyCall, request: SubscriptionProxyRequest) {
        delegate.handle(ctx, request)
    }

    private fun modelMappings(): List<PassThroughSubscriptionProxyProvider.ModelMapping> {
        val models = if (discoverModels) remoteModels() else staticModels.map(modelTransformer)
        return models.map { model ->
            PassThroughSubscriptionProxyProvider.ModelMapping(
                localId = localIdPrefix + model.id,
                upstreamId = model.id,
                supportedRoutes = routesFor(model.id),
                supportsFunctionCalling = model.supportsFunctionCalling,
                supportsToolChoice = model.supportsToolChoice,
                supportsVision = model.supportsVision,
                maxInputTokens = model.maxInputTokens,
                maxOutputTokens = model.maxOutputTokens,
                isDefault = model.isDefault,
            )
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun remoteModels(): List<StaticModel> {
        val now = Clock.System.now()
        val cached = modelCache
        if (cached != null && now - cached.fetchedAt < modelCacheTtl) {
            return cached.models
        }
        val token = apiKeyProvider().trimmedOrNull() ?: return cached?.models.orEmpty()
        val models = runCatching { fetchModels(token) }.getOrDefault(emptyList())
        if (models.isNotEmpty()) {
            modelCache = ModelCache(models, now)
        }
        return models.ifEmpty { cached?.models.orEmpty() }
    }

    private fun fetchModels(apiKey: String): List<StaticModel> {
        val builder = HttpRequest.newBuilder(URI.create(UrlResolver.resolveTargetUrl("/models", baseUri.toString())))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $apiKey")
            .GET()
        defaultHeaders.forEach { (name, value) -> builder.header(name, value) }
        val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) return emptyList()
        val root = JsonHelper.parseToJsonElementOrNull(response.body()) ?: return emptyList()
        val data = when (root) {
            is JsonObject -> root["data"] as? JsonArray ?: root["models"] as? JsonArray
            is JsonArray -> root
            else -> null
        } ?: return emptyList()
        return data.mapNotNull(::parseRemoteModel)
            .filter { includeModel(it.id) }
            .map(modelTransformer)
            .distinctBy { it.id }
    }

    private fun routesFor(upstreamId: String): Set<SubscriptionProxyRoute> {
        return supportedRoutes + extraRoutesForModel(upstreamId)
    }

    private fun parseRemoteModel(element: JsonElement): StaticModel? {
        val item = element as? JsonObject ?: return null
        val id = (item["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val modelType = (item["object"] as? JsonPrimitive)?.contentOrNull
        if (modelType == "embedding" || modelType == "embeddings") return null
        return StaticModel(
            id = id,
            maxInputTokens = intField(item, "max_input_tokens") ?: intField(item, "max_context_window_tokens"),
            maxOutputTokens = intField(item, "max_output_tokens"),
            isDefault = boolField(item, "is_default") ?: boolField(item, "default") ?: false,
        )
    }

    data class StaticModel(
        val id: String,
        val supportsFunctionCalling: Boolean = true,
        val supportsToolChoice: Boolean = true,
        val supportsVision: Boolean = false,
        val maxInputTokens: Int? = null,
        val maxOutputTokens: Int? = null,
        val isDefault: Boolean = false,
    )

    private data class ModelCache(
        val models: List<StaticModel>,
        val fetchedAt: Instant,
    )

    companion object {
        private val DEFAULT_HEADERS = mapOf(
            "Accept" to "application/json",
            "Content-Type" to JsonHelper.JSON_CONTENT_TYPE,
        )
        private val CACHE_TTL = 5.minutes
        private val DEFAULT_REQUEST_LOG_DIR = System.getProperty("java.io.tmpdir") +
            "/openai-usage-quota-intellij/subscription-proxy-openai-compatible-requests"

        private fun String?.trimmedOrNull(): String? = this?.trim()?.takeIf { it.isNotBlank() }

        private fun intField(item: JsonObject, name: String): Int? {
            return (item[name] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        }

        private fun boolField(item: JsonObject, name: String): Boolean? {
            return (item[name] as? JsonPrimitive)?.booleanOrNull
        }
    }
}
