package de.moritzf.quota.mistral.proxy

import de.moritzf.proxy.server.CompletionsHandler
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
import kotlinx.serialization.json.put

class MistralSubscriptionProxyProvider(
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
        nativeCompletionsRoute = SubscriptionProxyRoute.FIM_COMPLETIONS,
        jsonResponseTransformer = { request, raw ->
            if (request.route == SubscriptionProxyRoute.COMPLETIONS ||
                request.route == SubscriptionProxyRoute.FIM_COMPLETIONS
            ) {
                toTextCompletion(raw)
            } else {
                raw
            }
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

    companion object {
        const val ID = "mistral"
        const val PREFIX = "mi-"
        private const val DISPLAY_NAME = "Mistral"
        private const val LITELLM_PROVIDER = "mistral"
        val DEFAULT_UPSTREAM_BASE_URI: URI = URI.create("https://api.mistral.ai/v1")
        private val DEFAULT_REQUEST_LOG_DIR = System.getProperty("java.io.tmpdir") +
            "/openai-usage-quota-intellij/subscription-proxy-mistral-requests"

        internal fun toTextCompletion(raw: String): String {
            val root = JsonHelper.parseToJsonElementOrNull(raw) as? JsonObject ?: return raw
            val choices = root["choices"] as? JsonArray ?: return raw
            val choice = choices.firstOrNull() as? JsonObject ?: return raw
            if (choice["text"] is JsonPrimitive && choice["message"] == null) return raw
            val text = CompletionsHandler.chatMessageContent(raw)
            return JsonHelper.encodeToString(
                buildJsonObject {
                    root.forEach { (key, value) ->
                        when (key) {
                            "object" -> put("object", "text_completion")
                            "choices" -> put(
                                "choices",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("text", text)
                                            put("index", 0)
                                            put(
                                                "finish_reason",
                                                (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull ?: "stop",
                                            )
                                        },
                                    )
                                },
                            )
                            else -> put(key, value)
                        }
                    }
                    if ("object" !in root) put("object", "text_completion")
                },
            )
        }
    }
}
