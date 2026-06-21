package de.moritzf.quota.github.proxy

import de.moritzf.proxy.logging.RequestLogger
import de.moritzf.proxy.server.JsonHelper
import de.moritzf.proxy.subscription.PassThroughSubscriptionProxyProvider
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

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
            "X-GitHub-Api-Version" to API_VERSION,
            "Openai-Intent" to "conversation-edits",
            "x-initiator" to "user",
        ),
        requestHeadersProvider = ::requestHeaders,
        httpClient = httpClient,
        requestLogger = RequestLogger(fullRequestLogging, Path.of(requestLogDir)),
    )

    @Volatile private var modelCache: ModelCache? = null

    override val id: String = ID
    override val displayName: String = DISPLAY_NAME

    override fun isConfigured(): Boolean = delegate.isConfigured()

    override fun models() = delegate.models()

    override suspend fun handle(ctx: de.moritzf.proxy.server.ProxyCall, request: SubscriptionProxyRequest) {
        delegate.handle(ctx, request)
    }

    private fun modelMappings(): List<PassThroughSubscriptionProxyProvider.ModelMapping> {
        return remoteModels().map { model ->
            PassThroughSubscriptionProxyProvider.ModelMapping(
                localId = PREFIX + model.id,
                upstreamId = model.id,
                supportedRoutes = model.supportedRoutes,
                supportsFunctionCalling = true,
                supportsToolChoice = true,
                supportsVision = true,
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
        val request = HttpRequest.newBuilder(URI.create(UrlResolver.resolveTargetUrl("/models", upstreamBaseUri.toString())))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
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
        return data.mapNotNull { parseRemoteModel(it) }.distinctBy { it.id }
    }

    private fun parseRemoteModel(element: JsonElement): RemoteModel? {
        val item = element as? JsonObject ?: return null
        val id = (item["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        return RemoteModel(
            id = id,
            supportedRoutes = supportedRoutes(item["supported_endpoints"] as? JsonArray),
            maxInputTokens = intField(item, "max_input_tokens") ?: intField(item, "max_context_window_tokens"),
            maxOutputTokens = intField(item, "max_output_tokens"),
            isDefault = boolField(item, "is_default") ?: boolField(item, "default") ?: false,
        )
    }

    private fun supportedRoutes(endpoints: JsonArray?): Set<SubscriptionProxyRoute> {
        val values = endpoints?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty()
        if (values.isEmpty()) return setOf(SubscriptionProxyRoute.CHAT_COMPLETIONS)
        return buildSet {
            if (values.any { it.endsWith("/chat/completions") || it == "/chat/completions" }) {
                add(SubscriptionProxyRoute.CHAT_COMPLETIONS)
            }
            if (values.any { it.endsWith("/responses") || it == "/responses" }) {
                add(SubscriptionProxyRoute.RESPONSES)
            }
            if (values.any { it == "/v1/messages" || it == "/messages" }) {
                add(SubscriptionProxyRoute.ANTHROPIC_MESSAGES)
            }
            if (isEmpty()) add(SubscriptionProxyRoute.CHAT_COMPLETIONS)
        }
    }

    private fun requestHeaders(request: SubscriptionProxyRequest): Map<String, String> {
        return if (containsImageInput(request.body)) mapOf("Copilot-Vision-Request" to "true") else emptyMap()
    }

    private data class RemoteModel(
        val id: String,
        val supportedRoutes: Set<SubscriptionProxyRoute>,
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
        private const val DISPLAY_NAME = "GitHub Copilot"
        private const val LITELLM_PROVIDER = "github_copilot"
        private const val USER_AGENT = "openai-usage-quota-intellij"
        private const val API_VERSION = "2026-06-01"
        val DEFAULT_UPSTREAM_BASE_URI: URI = URI.create("https://api.githubcopilot.com")
        private val CACHE_TTL = 5.minutes
        private val DEFAULT_REQUEST_LOG_DIR = System.getProperty("java.io.tmpdir") +
            "/openai-usage-quota-intellij/subscription-proxy-github-requests"

        private fun String?.trimmedOrNull(): String? = this?.trim()?.takeIf { it.isNotBlank() }

        private fun intField(item: JsonObject, name: String): Int? {
            return (item[name] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        }

        private fun boolField(item: JsonObject, name: String): Boolean? {
            return (item[name] as? JsonPrimitive)?.booleanOrNull
        }

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
