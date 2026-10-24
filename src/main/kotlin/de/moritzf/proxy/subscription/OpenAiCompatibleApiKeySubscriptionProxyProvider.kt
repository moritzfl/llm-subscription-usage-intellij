package de.moritzf.proxy.subscription

import de.moritzf.proxy.logging.RequestLogger
import de.moritzf.proxy.model.ReasoningCapabilities
import de.moritzf.proxy.server.JsonHelper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
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
    private val supportedRoutes: Set<SubscriptionProxyRoute> =
        setOf(SubscriptionProxyRoute.CHAT_COMPLETIONS),
    private val nativeCompletionsRoute: SubscriptionProxyRoute = SubscriptionProxyRoute.COMPLETIONS,
    private val upstreamRouteProvider: (SubscriptionProxyRequest) -> SubscriptionProxyRoute = {
        it.route
    },
    private val upstreamUrlProvider: (SubscriptionProxyRequest) -> String? = { null },
    private val requestBodyTransformer: (SubscriptionProxyRequest, JsonObject) -> JsonObject =
        { _, body ->
            body
        },
    private val jsonResponseTransformer: ((SubscriptionProxyRequest, String) -> String)? = null,
    private val sseDataTransformer: ((SubscriptionProxyRequest, String) -> String)? = null,
    private val modelTransformer: (StaticModel) -> StaticModel = { it },
    private val includeModel: (String) -> Boolean = { true },
    private val extraRoutesForModel: (String) -> Set<SubscriptionProxyRoute> = { emptySet() },
    private val defaultHeaders: Map<String, String> = DEFAULT_HEADERS,
    private val httpClient: HttpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
    fullRequestLogging: Boolean = false,
    requestLogDir: String = DEFAULT_REQUEST_LOG_DIR,
    private val modelCacheTtl: kotlin.time.Duration = CACHE_TTL,
    private val modelRequestTimeout: Duration = Duration.ofSeconds(30),
    private val modelListEnricher: ((String, List<StaticModel>) -> List<StaticModel>)? = null,
    /** Levels for models whose endpoint states none, e.g. from a known-model table. */
    private val reasoningEffortsFallback: (String) -> List<String>? = { null },
) : SubscriptionProxyProvider {
    private val delegate =
        PassThroughSubscriptionProxyProvider(
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
            sseDataTransformer = sseDataTransformer,
            httpClient = httpClient,
            requestLogger = RequestLogger(fullRequestLogging, Path.of(requestLogDir)),
        )

    @Volatile private var modelCache: ModelCache? = null

    override fun isConfigured(): Boolean = delegate.isConfigured()

    override fun models(): List<SubscriptionProxyModel> = delegate.models()

    override fun fallbackModel(
        localId: String,
        route: SubscriptionProxyRoute,
    ): SubscriptionProxyModel? {
        if (
            localIdPrefix.isBlank() ||
                !localId.startsWith(localIdPrefix) ||
                localId.length == localIdPrefix.length
        ) {
            return null
        }
        val upstreamId = localId.removePrefix(localIdPrefix)
        if (!includeModel(upstreamId)) return null
        val routes = routesFor(upstreamId)
        if (route !in routes) return null
        val model = modelTransformer(StaticModel(upstreamId))
        return SubscriptionProxyModel(
            localId = localId,
            upstreamId = upstreamId,
            providerId = id,
            providerName = displayName,
            litellmProvider = litellmProvider,
            supportedRoutes = setOf(route),
            supportsFunctionCalling = model.supportsFunctionCalling,
            supportsToolChoice = model.supportsToolChoice,
            supportsVision = model.supportsVision,
            supportsPdfInput = model.supportsPdfInput,
            supportsParallelFunctionCalling = model.supportsParallelFunctionCalling,
            supportsPromptCaching = model.supportsPromptCaching,
            inputCostPerToken = model.inputCostPerToken,
            outputCostPerToken = model.outputCostPerToken,
            reasoningEfforts =
                model.acceptedReasoningEfforts() ?: reasoningEffortsFallback(model.id),
        )
    }

    override suspend fun handle(
        ctx: de.moritzf.proxy.server.ProxyCall,
        request: SubscriptionProxyRequest,
    ) {
        delegate.handle(ctx, request)
    }

    private fun modelMappings(): List<PassThroughSubscriptionProxyProvider.ModelMapping> {
        val models =
            if (discoverModels || modelListEnricher != null) remoteModels()
            else staticModels.map(modelTransformer)
        return models.map { model ->
            PassThroughSubscriptionProxyProvider.ModelMapping(
                localId = localIdPrefix + model.id,
                upstreamId = model.id,
                supportedRoutes = routesFor(model.id),
                supportsFunctionCalling = model.supportsFunctionCalling,
                supportsToolChoice = model.supportsToolChoice,
                supportsVision = model.supportsVision,
                supportsPdfInput = model.supportsPdfInput,
                supportsParallelFunctionCalling = model.supportsParallelFunctionCalling,
                supportsPromptCaching = model.supportsPromptCaching,
                inputCostPerToken = model.inputCostPerToken,
                outputCostPerToken = model.outputCostPerToken,
                maxInputTokens = model.maxInputTokens,
                maxOutputTokens = model.maxOutputTokens,
                isDefault = model.isDefault,
                reasoningEfforts =
                    model.acceptedReasoningEfforts() ?: reasoningEffortsFallback(model.id),
            )
        }
    }

    @Synchronized
    private fun remoteModels(): List<StaticModel> {
        val now = Clock.System.now()
        val token =
            apiKeyProvider().trimmedOrNull()
                ?: run {
                    modelCache = null
                    return emptyList()
                }
        val cached = modelCache?.takeIf { it.apiKey == token }
        if (cached != null && now - cached.fetchedAt < modelCacheTtl) {
            return cached.models
        }
        var fetchedAt = now
        val models =
            try {
                if (discoverModels) fetchModels(token)
                else modelListEnricher?.invoke(token, staticModels.map(modelTransformer)).orEmpty()
            } catch (cancelled: InterruptedException) {
                Thread.currentThread().interrupt()
                throw cancelled
            } catch (cancelled: java.util.concurrent.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the last list; without one, try again soon instead of hiding every model
                // for the whole cache lifetime.
                if (cached == null) {
                    fetchedAt = now - modelCacheTtl + minOf(FAILED_DISCOVERY_RETRY, modelCacheTtl)
                }
                cached?.models.orEmpty()
            }
        modelCache = ModelCache(models, fetchedAt, token)
        return models
    }

    /** Explicit discovery for settings: failures must be visible rather than an empty picker. */
    fun fetchModels(apiKey: String): List<StaticModel> {
        val builder =
            HttpRequest.newBuilder(URI.create(baseUri.toString().trimEnd('/') + "/models"))
                .timeout(modelRequestTimeout)
                .header("Authorization", "Bearer $apiKey")
                .GET()
        defaultHeaders.forEach { (name, value) -> builder.header(name, value) }
        val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) {
            when (response.statusCode()) {
                401,
                403 ->
                    "Model discovery was denied (HTTP ${response.statusCode()}). Check the API key and endpoint access."
                404,
                405 ->
                    "This endpoint does not expose /models. Check the base URL or enter model IDs manually."
                429 ->
                    "Model discovery is rate-limited (HTTP 429). Try again later or enter model IDs manually."
                else -> "Model discovery failed (HTTP ${response.statusCode()})."
            }
        }
        val root =
            JsonHelper.parseToJsonElementOrNull(response.body())
                ?: error("The /models endpoint returned invalid JSON. Check the base URL.")
        val data =
            when (root) {
                is JsonObject -> root["data"] as? JsonArray ?: root["models"] as? JsonArray
                is JsonArray -> root
                else -> null
            } ?: error("The /models response has no model list. Enter model IDs manually.")
        val models =
            data
                .mapNotNull(::parseRemoteModel)
                .filter { includeModel(it.id) }
                .map(modelTransformer)
                .distinctBy { it.id }
        return modelListEnricher?.invoke(apiKey, models) ?: models
    }

    private fun routesFor(upstreamId: String): Set<SubscriptionProxyRoute> {
        return supportedRoutes + extraRoutesForModel(upstreamId)
    }

    private fun parseRemoteModel(element: JsonElement): StaticModel? {
        val item = element as? JsonObject ?: return null
        val id =
            (item["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val modelType = (item["object"] as? JsonPrimitive)?.contentOrNull
        if (modelType == "embedding" || modelType == "embeddings") return null
        // Mistral reports what a model can do; models that cannot chat are left out.
        val capabilities = item["capabilities"] as? JsonObject
        if (capabilities?.let { boolField(it, "completion_chat") } == false) return null
        val tools = capabilities?.let { boolField(it, "function_calling") }
        return StaticModel(
            id = id,
            supportsFunctionCalling = tools ?: true,
            supportsToolChoice = tools ?: true,
            supportsVision = capabilities?.let { boolField(it, "vision") } ?: false,
            maxInputTokens = CONTEXT_FIELDS.firstNotNullOfOrNull { intField(item, it) },
            maxOutputTokens = intField(item, "max_output_tokens"),
            isDefault = boolField(item, "is_default") ?: boolField(item, "default") ?: false,
            supportsReasoning = capabilities?.let { boolField(it, "reasoning") },
            // OpenRouter- or LiteLLM-style endpoints state the levels themselves.
            reasoningEfforts = ReasoningCapabilities.fromModelEntry(item),
        )
    }

    data class StaticModel(
        val id: String,
        val supportsFunctionCalling: Boolean? = true,
        val supportsToolChoice: Boolean? = true,
        val supportsVision: Boolean? = false,
        val maxInputTokens: Int? = null,
        val maxOutputTokens: Int? = null,
        val isDefault: Boolean = false,
        val supportsParallelFunctionCalling: Boolean? = null,
        val supportsPromptCaching: Boolean? = null,
        val inputCostPerToken: Double? = null,
        val outputCostPerToken: Double? = null,
        val supportsPdfInput: Boolean? = null,
        /** Mistral-style `capabilities.reasoning`; null when the endpoint does not say. */
        val supportsReasoning: Boolean? = null,
        /** Levels a provider knows the model accepts; see [SubscriptionProxyModel]. */
        val reasoningEfforts: List<String>? = null,
    ) {
        /** A model reported as non-reasoning takes no effort; otherwise the levels are unknown. */
        fun acceptedReasoningEfforts(): List<String>? =
            reasoningEfforts ?: if (supportsReasoning == false) emptyList() else null
    }

    private data class ModelCache(
        val models: List<StaticModel>,
        val fetchedAt: Instant,
        val apiKey: String,
    )

    companion object {
        private val DEFAULT_HEADERS =
            mapOf(
                "Accept" to "application/json",
                "Content-Type" to JsonHelper.JSON_CONTENT_TYPE,
            )
        private val CACHE_TTL = 5.minutes
        private val FAILED_DISCOVERY_RETRY = 30.seconds
        private val DEFAULT_REQUEST_LOG_DIR =
            System.getProperty("java.io.tmpdir") +
                "/openai-usage-quota-intellij/subscription-proxy-openai-compatible-requests"

        private fun String?.trimmedOrNull(): String? = this?.trim()?.takeIf { it.isNotBlank() }

        /**
         * Context window names, in order: LiteLLM style, Mistral (`max_context_length`), OpenRouter
         * (`context_length`), and vLLM (`max_model_len`).
         */
        private val CONTEXT_FIELDS =
            listOf(
                "max_input_tokens",
                "max_context_window_tokens",
                "max_context_length",
                "context_length",
                "max_model_len",
            )

        private fun intField(item: JsonObject, name: String): Int? {
            return (item[name] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        }

        private fun boolField(item: JsonObject, name: String): Boolean? {
            return (item[name] as? JsonPrimitive)?.booleanOrNull
        }
    }
}
