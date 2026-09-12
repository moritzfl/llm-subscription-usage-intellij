package de.moritzf.quota.ollama.proxy

import de.moritzf.proxy.server.JsonHelper
import de.moritzf.proxy.subscription.OpenAiCompatibleApiKeySubscriptionProxyProvider
import de.moritzf.proxy.subscription.SubscriptionProxyProvider
import de.moritzf.proxy.subscription.SubscriptionProxyRequest
import de.moritzf.proxy.subscription.SubscriptionProxyRoute
import java.net.URI
import java.net.http.HttpClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

class OllamaSubscriptionProxyProvider(
    apiKeyProvider: () -> String?,
    httpClient: HttpClient = HttpClient.newHttpClient(),
    upstreamBaseUri: URI = DEFAULT_UPSTREAM_BASE_URI,
    fullRequestLogging: Boolean = false,
    requestLogDir: String = DEFAULT_REQUEST_LOG_DIR,
) : SubscriptionProxyProvider {
    private val delegate = OpenAiCompatibleApiKeySubscriptionProxyProvider(
        id = ID,
        displayName = DISPLAY_NAME,
        litellmProvider = LITELLM_PROVIDER,
        baseUri = upstreamBaseUri,
        apiKeyProvider = apiKeyProvider,
        localIdPrefix = PREFIX,
        modelTransformer = ::ollamaModelMetadata,
        upstreamUrlProvider = { request ->
            if (request.route == SubscriptionProxyRoute.COMPLETIONS) generateUrl(upstreamBaseUri) else null
        },
        requestBodyTransformer = { request, body ->
            if (request.route == SubscriptionProxyRoute.COMPLETIONS) toGenerateRequest(body) else body
        },
        jsonResponseTransformer = { request, raw ->
            if (request.route == SubscriptionProxyRoute.COMPLETIONS) toTextCompletion(raw) else raw
        },
        httpClient = httpClient,
        fullRequestLogging = fullRequestLogging,
        requestLogDir = requestLogDir,
    )

    override val id: String = ID
    override val displayName: String = DISPLAY_NAME

    override fun isConfigured(): Boolean = delegate.isConfigured()

    override fun models() = delegate.models()

    override fun fallbackModel(localId: String, route: SubscriptionProxyRoute) = delegate.fallbackModel(localId, route)

    override suspend fun handle(ctx: de.moritzf.proxy.server.ProxyCall, request: SubscriptionProxyRequest) {
        delegate.handle(ctx, request)
    }

    private fun ollamaModelMetadata(
        model: OpenAiCompatibleApiKeySubscriptionProxyProvider.StaticModel,
    ): OpenAiCompatibleApiKeySubscriptionProxyProvider.StaticModel {
        return if (model.id in MODELS_WITHOUT_TOOL_CALLS) {
            model.copy(supportsFunctionCalling = false, supportsToolChoice = false)
        } else {
            model
        }
    }

    companion object {
        const val ID = "ollama"
        const val PREFIX = "ol-"
        private const val DISPLAY_NAME = "Ollama"
        private const val LITELLM_PROVIDER = "ollama"
        val DEFAULT_UPSTREAM_BASE_URI: URI = URI.create("https://ollama.com/v1")
        private val DEFAULT_REQUEST_LOG_DIR = System.getProperty("java.io.tmpdir") +
            "/openai-usage-quota-intellij/subscription-proxy-ollama-requests"
        private val MODELS_WITHOUT_TOOL_CALLS = setOf(
            "deepseek-v3.2",
            "gemini-3-flash-preview",
            "gemma3:4b",
            "nemotron-3-nano:30b",
            "rnj-1:8b",
        )

        internal fun generateUrl(openAiV1Base: URI): String {
            val raw = openAiV1Base.toString().trimEnd('/')
            val root = if (raw.endsWith("/v1")) raw.dropLast(3).trimEnd('/') else raw
            return "$root/api/generate"
        }

        internal fun toGenerateRequest(body: JsonObject): JsonObject {
            val options = buildJsonObject {
                body["max_tokens"]?.let { token ->
                    val value = (token as? JsonPrimitive)?.intOrNull
                    if (value != null && value > 0) put("num_predict", value)
                }
                body["temperature"]?.let { put("temperature", it) }
                val stop = body["stop"]
                when (stop) {
                    is JsonPrimitive -> stop.contentOrNull?.takeIf { it.isNotEmpty() }?.let { put("stop", it) }
                    is JsonArray -> put("stop", stop)
                    else -> Unit
                }
            }
            return buildJsonObject {
                put("model", (body["model"] as? JsonPrimitive)?.content.orEmpty())
                put("prompt", (body["prompt"] as? JsonPrimitive)?.content.orEmpty())
                put("stream", false)
                (body["suffix"] as? JsonPrimitive)?.contentOrNull?.let { put("suffix", it) }
                if (options.isNotEmpty()) put("options", options)
            }
        }

        internal fun toTextCompletion(raw: String): String {
            val root = JsonHelper.parseToJsonElementOrNull(raw) as? JsonObject ?: return raw
            val text = (root["response"] as? JsonPrimitive)?.contentOrNull ?: return raw
            val model = (root["model"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            return JsonHelper.encodeToString(
                buildJsonObject {
                    put("id", "cmpl-ollama")
                    put("object", "text_completion")
                    put("created", System.currentTimeMillis() / 1000L)
                    put("model", model)
                    put("choices", buildJsonArray {
                        add(buildJsonObject {
                            put("text", text)
                            put("index", 0)
                            put("finish_reason", "stop")
                        })
                    })
                },
            )
        }
    }
}
