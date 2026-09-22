package de.moritzf.quota.opencode.proxy

import de.moritzf.proxy.subscription.SubscriptionProxyModel
import de.moritzf.proxy.subscription.SubscriptionProxyRoute
import de.moritzf.quota.shared.JsonSupport
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.net.URI

/** Only the Console fields needed to route models; credentials come from the active OAuth login. */
@Serializable
internal data class OpenCodeConsoleConfig(val providers: Map<String, ConsoleProvider>)

@Serializable
internal data class ConsoleProvider(
    @SerialName("package") val packageName: String? = null,
    val settings: ConsoleSettings? = null,
    val headers: Map<String, String> = emptyMap(),
    val body: JsonObject = JsonObject(emptyMap()),
    val models: Map<String, ConsoleModel> = emptyMap(),
)

@Serializable
internal data class ConsoleSettings(val baseURL: String? = null)

@Serializable
internal data class ConsoleModel(
    val modelID: String? = null,
    @SerialName("package") val packageName: String? = null,
    val settings: ConsoleSettings? = null,
    val headers: Map<String, String> = emptyMap(),
    val body: JsonObject = JsonObject(emptyMap()),
    val disabled: Boolean = false,
    val capabilities: ConsoleCapabilities? = null,
    val limit: ConsoleLimit? = null,
)

@Serializable
internal data class ConsoleCapabilities(val tools: Boolean = true, val input: List<String> = emptyList())

@Serializable
internal data class ConsoleLimit(val context: Int? = null, val input: Int? = null, val output: Int? = null)

internal data class OpenCodeConsoleModel(
    val model: SubscriptionProxyModel,
    val nativeRoute: SubscriptionProxyRoute,
    val baseUri: URI,
    val headers: Map<String, String>,
    val body: JsonObject,
) {
    val targetUri: URI get() = URI.create(baseUri.toString().trimEnd('/') + nativeRoute.normalizedPath)

    companion object {
        fun parse(body: String): List<OpenCodeConsoleModel> {
            val provider = JsonSupport.json.decodeFromString<OpenCodeConsoleConfig>(body).providers["opencode"]
                ?: return emptyList()
            return provider.models.mapNotNull { (id, model) ->
                if (model.disabled) return@mapNotNull null
                val nativeRoute = when (model.packageName ?: provider.packageName) {
                    "aisdk:@ai-sdk/openai-compatible", "@opencode/ai/providers/openai-compatible" -> SubscriptionProxyRoute.CHAT_COMPLETIONS
                    "aisdk:@ai-sdk/openai", "@opencode/ai/providers/openai", "@opencode/ai/providers/openai-compatible-responses" -> SubscriptionProxyRoute.RESPONSES
                    "aisdk:@ai-sdk/anthropic", "@opencode/ai/providers/anthropic", "@opencode/ai/providers/anthropic-compatible" -> SubscriptionProxyRoute.ANTHROPIC_MESSAGES
                    else -> return@mapNotNull null
                }
                val base = model.settings?.baseURL ?: provider.settings?.baseURL ?: return@mapNotNull null
                val uri = URI.create(base)
                require(uri.scheme in setOf("https", "http") && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null) {
                    "Invalid OpenCode inference URL"
                }
                OpenCodeConsoleModel(
                    model = SubscriptionProxyModel(
                        localId = OpenCodeZenSubscriptionProxyProvider.PREFIX + id,
                        upstreamId = model.modelID ?: id,
                        providerId = OpenCodeZenSubscriptionProxyProvider.ID,
                        providerName = "OpenCode Zen",
                        litellmProvider = "opencode",
                        supportedRoutes = setOf(SubscriptionProxyRoute.CHAT_COMPLETIONS, nativeRoute),
                        supportsFunctionCalling = model.capabilities?.tools ?: true,
                        supportsToolChoice = model.capabilities?.tools ?: true,
                        supportsVision = model.capabilities?.input?.contains("image") == true,
                        maxInputTokens = model.limit?.input ?: model.limit?.context,
                        maxOutputTokens = model.limit?.output,
                    ),
                    nativeRoute = nativeRoute,
                    baseUri = uri,
                    headers = provider.headers + model.headers,
                    body = JsonObject(provider.body + model.body),
                )
            }
        }
    }
}
