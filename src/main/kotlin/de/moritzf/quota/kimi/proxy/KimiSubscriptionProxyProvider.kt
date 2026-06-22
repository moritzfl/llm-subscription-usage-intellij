package de.moritzf.quota.kimi.proxy

import de.moritzf.proxy.logging.RequestLogger
import de.moritzf.proxy.server.JsonHelper
import de.moritzf.proxy.subscription.PassThroughSubscriptionProxyProvider
import de.moritzf.proxy.subscription.SubscriptionProxyModel
import de.moritzf.proxy.subscription.SubscriptionProxyProvider
import de.moritzf.proxy.subscription.SubscriptionProxyRequest
import de.moritzf.proxy.subscription.SubscriptionProxyRoute
import de.moritzf.quota.kimi.KimiCredentialRefresher
import de.moritzf.quota.kimi.KimiCredentials
import de.moritzf.quota.kimi.KimiDeviceHeaders
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

class KimiSubscriptionProxyProvider(
    private val credentialsProvider: () -> KimiCredentials?,
    private val credentialsSaver: (KimiCredentials) -> Unit = {},
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val openAiCompatibleBaseUri: URI = OPENAI_COMPATIBLE_BASE_URI,
    anthropicCompatibleBaseUri: URI = ANTHROPIC_COMPATIBLE_BASE_URI,
    private val modelsDevCatalogUri: URI? = MODELS_DEV_CATALOG_URI,
    fullRequestLogging: Boolean = false,
    requestLogDir: String = DEFAULT_REQUEST_LOG_DIR,
) : SubscriptionProxyProvider {
    private val credentialRefresher = KimiCredentialRefresher(httpClient)
    private val requestLogger = RequestLogger(fullRequestLogging, Path.of(requestLogDir))
    private val chatDelegate = PassThroughSubscriptionProxyProvider(
        id = ID,
        displayName = DISPLAY_NAME,
        litellmProvider = LITELLM_PROVIDER,
        baseUri = openAiCompatibleBaseUri,
        accessTokenProvider = ::accessToken,
        tokenRefresher = ::refreshAfterUnauthorized,
        modelMappingsProvider = ::modelMappings,
        defaultHeaders = defaultHeaders(),
        httpClient = httpClient,
        requestLogger = requestLogger,
    )
    private val messagesDelegate = PassThroughSubscriptionProxyProvider(
        id = ID,
        displayName = DISPLAY_NAME,
        litellmProvider = LITELLM_PROVIDER,
        baseUri = anthropicCompatibleBaseUri,
        accessTokenProvider = ::accessToken,
        tokenRefresher = ::refreshAfterUnauthorized,
        modelMappingsProvider = ::modelMappings,
        defaultHeaders = defaultHeaders(),
        httpClient = httpClient,
        requestLogger = requestLogger,
    )

    @Volatile private var modelCache: ModelCache? = null

    override val id: String = ID
    override val displayName: String = DISPLAY_NAME

    override fun isConfigured(): Boolean = credentialsProvider()?.isUsable() == true

    override fun models() = chatDelegate.models()

    override fun fallbackModel(localId: String, route: SubscriptionProxyRoute): SubscriptionProxyModel? {
        if (route != SubscriptionProxyRoute.ANTHROPIC_MESSAGES) return null
        val upstreamId = localId.trim()
            .takeIf { it.startsWith(PREFIX) && it.length > PREFIX.length }
            ?.removePrefix(PREFIX)
            ?: return null
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
            supportsPromptCaching = true,
            maxInputTokens = MAX_INPUT_TOKENS,
            maxOutputTokens = MAX_OUTPUT_TOKENS,
        )
    }

    override suspend fun handle(ctx: de.moritzf.proxy.server.ProxyCall, request: SubscriptionProxyRequest) {
        when (request.route) {
            SubscriptionProxyRoute.ANTHROPIC_MESSAGES -> messagesDelegate.handle(ctx, request)
            SubscriptionProxyRoute.CHAT_COMPLETIONS,
            SubscriptionProxyRoute.RESPONSES -> chatDelegate.handle(ctx, request)
        }
    }

    private fun accessToken(): String? {
        val credentials = credentialsProvider() ?: return null
        val refreshed = runCatching { credentialRefresher.refreshIfNeeded(credentials) }.getOrDefault(credentials)
        if (refreshed != credentials) credentialsSaver(refreshed)
        return refreshed.accessToken.trim().takeIf { it.isNotBlank() }
    }

    private fun refreshAfterUnauthorized(@Suppress("UNUSED_PARAMETER") staleAccessToken: String?): String? {
        val credentials = credentialsProvider() ?: return null
        val refreshed = runCatching { credentialRefresher.refresh(credentials) }.getOrNull() ?: return null
        credentialsSaver(refreshed)
        return refreshed.accessToken.trim().takeIf { it.isNotBlank() }
    }

    private fun modelMappings(): List<PassThroughSubscriptionProxyProvider.ModelMapping> {
        val models = remoteModels().ifEmpty { DEFAULT_MODELS }
        val defaultIndex = models.indexOfFirst { it.isDefault }.takeIf { it >= 0 } ?: 0
        return models.mapIndexed { index, model ->
            PassThroughSubscriptionProxyProvider.ModelMapping(
                localId = PREFIX + model.id,
                upstreamId = model.id,
                supportedRoutes = SUPPORTED_ROUTES,
                supportsFunctionCalling = model.supportsToolUse,
                supportsToolChoice = model.supportsToolUse,
                supportsVision = model.supportsVision,
                supportsPromptCaching = true,
                maxInputTokens = model.maxInputTokens,
                maxOutputTokens = model.maxOutputTokens,
                isDefault = index == defaultIndex,
            )
        }
    }

    private fun remoteModels(): List<RemoteModel> {
        val now = System.currentTimeMillis()
        val cached = modelCache
        if (cached != null && now - cached.fetchedAtMillis < CACHE_TTL_MILLIS) {
            return cached.models
        }
        val token = accessToken() ?: return emptyList()
        val firstPartyModels = runCatching { fetchModels(token) }.getOrDefault(emptyList())
        val models = if (firstPartyModels.hasConcreteModelIds()) {
            firstPartyModels
        } else {
            val catalogModels = modelsDevCatalogUri
                ?.let { uri -> runCatching { fetchModelsDevCatalogModels(uri) }.getOrDefault(emptyList()) }
                .orEmpty()
            if (catalogModels.isEmpty()) {
                firstPartyModels
            } else {
                (catalogModels + firstPartyModels.ifEmpty { DEFAULT_MODELS }).distinctBy { it.id }
            }
        }
        if (models.isNotEmpty()) {
            modelCache = ModelCache(models, now)
        }
        return models
    }

    private fun fetchModels(token: String): List<RemoteModel> {
        val request = HttpRequest.newBuilder(URI.create(resolveUpstreamUrl(openAiCompatibleBaseUri, "/models")))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) return emptyList()
        val root = JsonHelper.parseToJsonElementOrNull(response.body()) as? JsonObject ?: return emptyList()
        val data = root["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull(::parseRemoteModel).distinctBy { it.id }
    }

    private fun fetchModelsDevCatalogModels(uri: URI): List<RemoteModel> {
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) return emptyList()
        val root = JsonHelper.parseToJsonElementOrNull(response.body()) as? JsonObject ?: return emptyList()
        val provider = root[MODELS_DEV_PROVIDER_ID] as? JsonObject ?: return emptyList()
        val models = provider["models"] as? JsonObject ?: return emptyList()
        return models.mapNotNull { (id, element) -> parseModelsDevModel(id, element) }
    }

    private fun parseRemoteModel(element: JsonElement): RemoteModel? {
        val item = element as? JsonObject ?: return null
        val id = stringField(item, "id") ?: return null
        val maxInputTokens = intField(item, "context_length") ?: return null
        if (maxInputTokens <= 0) return null
        val supportsToolUse = boolField(item, "supports_tool_use") ?: true
        return RemoteModel(
            id = id,
            maxInputTokens = maxInputTokens,
            maxOutputTokens = intField(item, "max_output_tokens") ?: MAX_OUTPUT_TOKENS,
            supportsToolUse = supportsToolUse,
            supportsVision = boolField(item, "supports_image_in") == true || boolField(item, "supports_video_in") == true,
            isDefault = id == MODEL_ID,
        )
    }

    private fun parseModelsDevModel(key: String, element: JsonElement): RemoteModel? {
        val item = element as? JsonObject ?: return null
        val id = stringField(item, "id") ?: key.takeIf { it.isNotBlank() } ?: return null
        val limits = item["limit"] as? JsonObject
        val maxInputTokens = limits?.let { intField(it, "context") }
            ?: intField(item, "context_length")
            ?: return null
        if (maxInputTokens <= 0) return null
        return RemoteModel(
            id = id,
            maxInputTokens = maxInputTokens,
            maxOutputTokens = limits?.let { intField(it, "output") } ?: intField(item, "max_output_tokens") ?: MAX_OUTPUT_TOKENS,
            supportsToolUse = boolField(item, "tool_call") ?: true,
            supportsVision = boolField(item, "attachment") == true,
        )
    }

    private fun List<RemoteModel>.hasConcreteModelIds(): Boolean {
        return any { it.id != MODEL_ID }
    }

    private data class RemoteModel(
        val id: String,
        val maxInputTokens: Int,
        val maxOutputTokens: Int,
        val supportsToolUse: Boolean,
        val supportsVision: Boolean,
        val isDefault: Boolean = false,
    )

    private data class ModelCache(
        val models: List<RemoteModel>,
        val fetchedAtMillis: Long,
    )

    private fun resolveUpstreamUrl(baseUri: URI, path: String): String {
        return baseUri.toString().trimEnd('/') + "/" + path.trimStart('/')
    }

    companion object {
        const val ID = "kimi"
        const val PREFIX = "ki-"
        const val MODEL_ID = "kimi-for-coding"
        private const val DISPLAY_NAME = "Kimi Code"
        private const val LITELLM_PROVIDER = "kimi"
        private const val MODELS_DEV_PROVIDER_ID = "kimi-for-coding"
        private const val MAX_INPUT_TOKENS = 262_144
        private const val MAX_OUTPUT_TOKENS = 65_536
        private const val CACHE_TTL_MILLIS = 5 * 60 * 1000L
        private val SUPPORTED_ROUTES = setOf(SubscriptionProxyRoute.CHAT_COMPLETIONS, SubscriptionProxyRoute.ANTHROPIC_MESSAGES)
        private val DEFAULT_MODELS = listOf(
            RemoteModel(
                id = MODEL_ID,
                maxInputTokens = MAX_INPUT_TOKENS,
                maxOutputTokens = MAX_OUTPUT_TOKENS,
                supportsToolUse = true,
                supportsVision = true,
                isDefault = true,
            ),
        )
        private val OPENAI_COMPATIBLE_BASE_URI = URI.create("https://api.kimi.com/coding/v1")
        private val ANTHROPIC_COMPATIBLE_BASE_URI = URI.create("https://api.kimi.com/coding")
        private val MODELS_DEV_CATALOG_URI = URI.create("https://models.dev/api.json")
        private val DEFAULT_REQUEST_LOG_DIR = System.getProperty("java.io.tmpdir") +
            "/openai-usage-quota-intellij/subscription-proxy-kimi-requests"

        private fun defaultHeaders(): Map<String, String> = mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "User-Agent" to "KimiCLI/1.40.0",
        ) + KimiDeviceHeaders.all()

        private fun intField(item: JsonObject, name: String): Int? {
            return (item[name] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        }

        private fun boolField(item: JsonObject, name: String): Boolean? {
            return (item[name] as? JsonPrimitive)?.booleanOrNull
        }

        private fun stringField(item: JsonObject, name: String): String? {
            return (item[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }
    }
}
