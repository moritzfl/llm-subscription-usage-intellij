package de.moritzf.quota.zai.proxy

import de.moritzf.proxy.server.JsonHelper
import de.moritzf.proxy.subscription.OpenAiCompatibleApiKeySubscriptionProxyProvider
import de.moritzf.proxy.subscription.OpenAiCompatibleApiKeySubscriptionProxyProvider.StaticModel
import de.moritzf.proxy.subscription.SubscriptionProxyProvider
import de.moritzf.proxy.subscription.SubscriptionProxyRequest
import de.moritzf.proxy.subscription.SubscriptionProxyRoute
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Z.ai GLM Coding Plan through its plan endpoint. Requests to the standard API are billed to the
 * account balance even while plan quota remains.
 */
class ZaiSubscriptionProxyProvider(
    apiKeyProvider: () -> String?,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val upstreamBaseUri: URI = DEFAULT_UPSTREAM_BASE_URI,
    fullRequestLogging: Boolean = false,
    requestLogDir: String = DEFAULT_REQUEST_LOG_DIR,
) : SubscriptionProxyProvider {
    private val delegate =
        OpenAiCompatibleApiKeySubscriptionProxyProvider(
            id = ID,
            displayName = DISPLAY_NAME,
            litellmProvider = LITELLM_PROVIDER,
            baseUri = upstreamBaseUri,
            apiKeyProvider = apiKeyProvider,
            localIdPrefix = PREFIX,
            staticModels = STATIC_MODELS,
            discoverModels = false,
            modelListEnricher = ::planModels,
            requestBodyTransformer = { _, body -> alwaysThinking(body) },
            httpClient = httpClient,
            fullRequestLogging = fullRequestLogging,
            requestLogDir = requestLogDir,
        )

    override val id: String = ID
    override val displayName: String = DISPLAY_NAME

    override fun isConfigured(): Boolean = delegate.isConfigured()

    override fun models() = delegate.models()

    override fun fallbackModel(localId: String, route: SubscriptionProxyRoute) =
        delegate.fallbackModel(localId, route)

    override suspend fun handle(
        ctx: de.moritzf.proxy.server.ProxyCall,
        request: SubscriptionProxyRequest,
    ) {
        delegate.handle(ctx, request)
    }

    /**
     * The models the plan endpoint lists, keeping known limits; the documented plan models when it
     * lists none or cannot be read.
     */
    private fun planModels(apiKey: String, known: List<StaticModel>): List<StaticModel> {
        val ids = runCatching {
            val request =
                HttpRequest.newBuilder(
                        URI.create(upstreamBaseUri.toString().trimEnd('/') + "/models")
                    )
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer $apiKey")
                    .header("Accept", "application/json")
                    .GET()
                    .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) return known
            modelIds(response.body())
        }
            .getOrElse { exception ->
                if (exception is InterruptedException) Thread.currentThread().interrupt()
                return known
            }
        if (ids.isEmpty()) return known
        val byId = known.associateBy { it.id }
        val defaultId = known.firstOrNull { it.isDefault && it.id in ids }?.id ?: ids.maxOrNull()
        return ids.map { id -> (byId[id] ?: StaticModel(id)).copy(isDefault = id == defaultId) }
    }

    companion object {
        const val ID = "zai"
        const val PREFIX = "za-"
        private const val DISPLAY_NAME = "Z.ai"
        private const val LITELLM_PROVIDER = "zai"
        val DEFAULT_UPSTREAM_BASE_URI: URI = URI.create("https://api.z.ai/api/coding/paas/v4")

        /** The pay-as-you-go API, used for model discovery of vision and document models. */
        val STANDARD_API_BASE_URI: URI = URI.create("https://api.z.ai/api/paas/v4")
        private val DEFAULT_REQUEST_LOG_DIR =
            System.getProperty("java.io.tmpdir") +
                "/openai-usage-quota-intellij/subscription-proxy-zai-requests"

        /** The plan serves GLM-5.3 and GLM-5.3-Flash; older GLM models are routed to these. */
        private val STATIC_MODELS =
            listOf(
                StaticModel(
                    id = "glm-5.3",
                    maxInputTokens = 1_000_000,
                    maxOutputTokens = 131_072,
                    isDefault = true,
                ),
                StaticModel(id = "glm-5.3-flash"),
            )

        internal fun modelIds(body: String): List<String> {
            val root = JsonHelper.parseToJsonElementOrNull(body)
            val data =
                when (root) {
                    is JsonObject -> root["data"] as? JsonArray ?: root["models"] as? JsonArray
                    is JsonArray -> root
                    else -> null
                }
            return data
                .orEmpty()
                .mapNotNull { entry ->
                    ((entry as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull?.trim()
                }
                .filter { it.startsWith("glm-", ignoreCase = true) }
                .distinct()
        }

        /**
         * GLM-5.3 always thinks and rejects `thinking.type: disabled`; clients that turn thinking
         * off get the lowest effort instead.
         */
        internal fun alwaysThinking(body: JsonObject): JsonObject {
            val model = (body["model"] as? JsonPrimitive)?.contentOrNull ?: return body
            if (!model.startsWith("glm-5.3", ignoreCase = true)) return body
            val thinking = body["thinking"] as? JsonObject ?: return body
            if ((thinking["type"] as? JsonPrimitive)?.contentOrNull != "disabled") return body
            return JsonObject(
                body +
                    mapOf(
                        "thinking" to JsonObject(thinking + ("type" to JsonPrimitive("enabled")))
                    ) +
                    if ("reasoning_effort" in body) emptyMap()
                    else mapOf("reasoning_effort" to JsonPrimitive("low"))
            )
        }
    }
}
