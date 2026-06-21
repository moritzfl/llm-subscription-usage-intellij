package de.moritzf.proxy.subscription

enum class SubscriptionProxyRoute(
    val normalizedPath: String,
    val upstreamPath: String,
) {
    CHAT_COMPLETIONS("/chat/completions", "/chat/completions"),
    RESPONSES("/responses", "/responses"),
    ANTHROPIC_MESSAGES("/messages", "/v1/messages"),
}

data class SubscriptionProxyModel(
    val localId: String,
    val upstreamId: String,
    val providerId: String,
    val providerName: String,
    val litellmProvider: String,
    val supportedRoutes: Set<SubscriptionProxyRoute>,
    val supportsFunctionCalling: Boolean = true,
    val supportsParallelFunctionCalling: Boolean = false,
    val supportsToolChoice: Boolean = true,
    val supportsVision: Boolean = true,
    val supportsPromptCaching: Boolean = false,
    val maxInputTokens: Int? = null,
    val maxOutputTokens: Int? = null,
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

    suspend fun handle(ctx: de.moritzf.proxy.server.ProxyCall, request: SubscriptionProxyRequest)
}
