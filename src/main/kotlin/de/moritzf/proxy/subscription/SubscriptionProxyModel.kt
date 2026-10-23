package de.moritzf.proxy.subscription

enum class SubscriptionProxyRoute(
    val normalizedPath: String,
    val upstreamPath: String,
) {
    CHAT_COMPLETIONS("/chat/completions", "/chat/completions"),
    RESPONSES("/responses", "/responses"),
    COMPLETIONS("/completions", "/completions"),
    FIM_COMPLETIONS("/fim/completions", "/fim/completions"),
    ANTHROPIC_MESSAGES("/messages", "/v1/messages"),
}

data class SubscriptionProxyModel(
    val localId: String,
    val upstreamId: String,
    val providerId: String,
    val providerName: String,
    val litellmProvider: String,
    val supportedRoutes: Set<SubscriptionProxyRoute>,
    val supportsFunctionCalling: Boolean? = true,
    val supportsParallelFunctionCalling: Boolean? = null,
    val supportsToolChoice: Boolean? = true,
    val supportsVision: Boolean? = true,
    val supportsPromptCaching: Boolean? = null,
    val inputCostPerToken: Double? = null,
    val outputCostPerToken: Double? = null,
    val maxInputTokens: Int? = null,
    val maxOutputTokens: Int? = null,
    val isDefault: Boolean = false,
    val supportsPdfInput: Boolean? = null,
    /**
     * Reasoning efforts the model accepts. Null when unknown: the client's value is forwarded.
     * Empty when the model takes none: the value is dropped. Otherwise it moves to the closest one.
     */
    val reasoningEfforts: List<String>? = null,
)

data class SubscriptionProxyRequest(
    val route: SubscriptionProxyRoute,
    val requestId: String,
    val model: SubscriptionProxyModel,
    val body: kotlinx.serialization.json.JsonObject,
)

interface SubscriptionProxyProvider {
    val id: String
    val displayName: String

    fun isConfigured(): Boolean

    fun models(): List<SubscriptionProxyModel>

    fun fallbackModel(localId: String, route: SubscriptionProxyRoute): SubscriptionProxyModel? =
        null

    suspend fun handle(ctx: de.moritzf.proxy.server.ProxyCall, request: SubscriptionProxyRequest)
}
