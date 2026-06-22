package de.moritzf.quota.zai.proxy

import de.moritzf.proxy.subscription.OpenAiCompatibleApiKeySubscriptionProxyProvider
import de.moritzf.proxy.subscription.SubscriptionProxyProvider
import de.moritzf.proxy.subscription.SubscriptionProxyRequest
import de.moritzf.proxy.subscription.SubscriptionProxyRoute
import java.net.URI
import java.net.http.HttpClient

class ZaiSubscriptionProxyProvider(
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
        staticModels = STATIC_MODELS,
        discoverModels = false,
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
        const val ID = "zai"
        const val PREFIX = "za-"
        private const val DISPLAY_NAME = "Z.ai"
        private const val LITELLM_PROVIDER = "zai"
        val DEFAULT_UPSTREAM_BASE_URI: URI = URI.create("https://api.z.ai/api/paas/v4")
        private val DEFAULT_REQUEST_LOG_DIR = System.getProperty("java.io.tmpdir") +
            "/openai-usage-quota-intellij/subscription-proxy-zai-requests"
        private val STATIC_MODELS = listOf(
            static("glm-5.2", input = 200_000, output = 131_072, default = true),
            static("glm-5.1", input = 200_000, output = 131_072),
            static("glm-5-turbo", input = 200_000, output = 131_072),
            static("glm-5", input = 204_800, output = 131_072),
            static("glm-4.7", input = 204_800, output = 131_072),
            static("glm-4.7-flash", input = 200_000, output = 131_072),
            static("glm-4.7-flashx", input = 200_000, output = 131_072),
            static("glm-4.6", input = 204_800, output = 131_072),
            static("glm-4.5", input = 131_072, output = 98_304),
            static("glm-4.5-air", input = 131_072, output = 98_304),
            static("glm-4.5-x", input = 131_072, output = 98_304),
            static("glm-4.5-airx", input = 131_072, output = 98_304),
            static("glm-4.5-flash", input = 131_072, output = 98_304),
            static("glm-4-32b-0414-128k", input = 131_072, output = 98_304),
        )

        private fun static(id: String, input: Int, output: Int, default: Boolean = false) =
            OpenAiCompatibleApiKeySubscriptionProxyProvider.StaticModel(
                id = id,
                maxInputTokens = input,
                maxOutputTokens = output,
                isDefault = default,
            )
    }
}
