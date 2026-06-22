package de.moritzf.quota.minimax.proxy

import de.moritzf.proxy.subscription.OpenAiCompatibleApiKeySubscriptionProxyProvider
import de.moritzf.proxy.subscription.SubscriptionProxyProvider
import de.moritzf.proxy.subscription.SubscriptionProxyRequest
import de.moritzf.proxy.subscription.SubscriptionProxyRoute
import de.moritzf.quota.minimax.MiniMaxRegion
import java.net.URI
import java.net.http.HttpClient

class MiniMaxSubscriptionProxyProvider(
    apiKeyProvider: () -> String?,
    regionProvider: () -> MiniMaxRegion,
    httpClient: HttpClient = HttpClient.newHttpClient(),
    upstreamBaseUri: URI? = null,
    fullRequestLogging: Boolean = false,
    requestLogDir: String = DEFAULT_REQUEST_LOG_DIR,
) : SubscriptionProxyProvider {
    private val delegate = OpenAiCompatibleApiKeySubscriptionProxyProvider(
        id = ID,
        displayName = DISPLAY_NAME,
        litellmProvider = LITELLM_PROVIDER,
        baseUri = upstreamBaseUri ?: baseUriFor(regionProvider()),
        apiKeyProvider = apiKeyProvider,
        localIdPrefix = PREFIX,
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
        const val ID = "minimax"
        const val PREFIX = "mm-"
        private const val DISPLAY_NAME = "MiniMax"
        private const val LITELLM_PROVIDER = "minimax"
        private val GLOBAL_BASE_URI = URI.create("https://api.minimax.io/v1")
        private val CN_BASE_URI = URI.create("https://api.minimaxi.com/v1")
        private val DEFAULT_REQUEST_LOG_DIR = System.getProperty("java.io.tmpdir") +
            "/openai-usage-quota-intellij/subscription-proxy-minimax-requests"

        fun baseUriFor(region: MiniMaxRegion): URI = when (region) {
            MiniMaxRegion.GLOBAL -> GLOBAL_BASE_URI
            MiniMaxRegion.CN -> CN_BASE_URI
        }
    }
}
