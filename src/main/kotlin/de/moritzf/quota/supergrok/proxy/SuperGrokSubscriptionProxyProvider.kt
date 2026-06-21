package de.moritzf.quota.supergrok.proxy

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class SuperGrokSubscriptionProxyProvider(
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
            "User-Agent" to "openai-usage-quota-intellij",
        ),
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
        val ids = modelIds().ifEmpty { DEFAULT_MODELS }
        return ids.map { id ->
            PassThroughSubscriptionProxyProvider.ModelMapping(
                localId = id,
                upstreamId = id,
                supportedRoutes = setOf(SubscriptionProxyRoute.CHAT_COMPLETIONS, SubscriptionProxyRoute.RESPONSES),
                supportsPromptCaching = false,
            )
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun modelIds(): List<String> {
        val now = Clock.System.now()
        val cached = modelCache
        if (cached != null && now - cached.fetchedAt < CACHE_TTL) {
            return cached.ids
        }
        val token = accessTokenProvider().trimmedOrNull() ?: return emptyList()
        val ids = runCatching { fetchModelIds(token) }.getOrDefault(emptyList())
        if (ids.isNotEmpty()) {
            modelCache = ModelCache(ids, now)
        }
        return ids
    }

    private fun fetchModelIds(token: String): List<String> {
        val request = HttpRequest.newBuilder(URI.create(UrlResolver.resolveTargetUrl("/models", upstreamBaseUri.toString())))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .header("User-Agent", "openai-usage-quota-intellij")
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) return emptyList()
        val root = JsonHelper.parseToJsonElementOrNull(response.body()) as? JsonObject ?: return emptyList()
        val data = root["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { item ->
            val id = (item as? JsonObject)?.get("id") as? JsonPrimitive
            id?.contentOrNull?.takeIf { it.isNotBlank() }
        }.distinct()
    }

    private data class ModelCache(
        val ids: List<String>,
        val fetchedAt: Instant,
    )

    companion object {
        const val ID = "supergrok"
        private const val DISPLAY_NAME = "SuperGrok"
        private const val LITELLM_PROVIDER = "xai"
        val DEFAULT_UPSTREAM_BASE_URI: URI = URI.create("https://api.x.ai/v1")
        private val DEFAULT_MODELS = listOf("grok-4.3")
        private val CACHE_TTL = 5.minutes
        private val DEFAULT_REQUEST_LOG_DIR = System.getProperty("java.io.tmpdir") +
            "/openai-usage-quota-intellij/subscription-proxy-supergrok-requests"

        private fun String?.trimmedOrNull(): String? = this?.trim()?.takeIf { it.isNotBlank() }
    }
}
