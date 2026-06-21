package de.moritzf.quota.kimi.proxy

import de.moritzf.proxy.logging.RequestLogger
import de.moritzf.proxy.subscription.PassThroughSubscriptionProxyProvider
import de.moritzf.proxy.subscription.SubscriptionProxyProvider
import de.moritzf.proxy.subscription.SubscriptionProxyRequest
import de.moritzf.proxy.subscription.SubscriptionProxyRoute
import de.moritzf.quota.kimi.KimiCredentialRefresher
import de.moritzf.quota.kimi.KimiCredentials
import de.moritzf.quota.kimi.KimiDeviceHeaders
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Path

class KimiSubscriptionProxyProvider(
    private val credentialsProvider: () -> KimiCredentials?,
    private val credentialsSaver: (KimiCredentials) -> Unit = {},
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    openAiCompatibleBaseUri: URI = OPENAI_COMPATIBLE_BASE_URI,
    anthropicCompatibleBaseUri: URI = ANTHROPIC_COMPATIBLE_BASE_URI,
    fullRequestLogging: Boolean = false,
    requestLogDir: String = DEFAULT_REQUEST_LOG_DIR,
) : SubscriptionProxyProvider {
    private val credentialRefresher = KimiCredentialRefresher(httpClient)
    private val requestLogger = RequestLogger(fullRequestLogging, Path.of(requestLogDir))
    private val chatDelegate = PassThroughSubscriptionProxyProvider(
        id = ID,
        displayName = DISPLAY_NAME,
        litellmProvider = LITELLM_PROVIDER,
        baseUri = openAiCompatibleBaseUri,
        accessTokenProvider = ::accessToken,
        tokenRefresher = ::refreshAfterUnauthorized,
        modelMappingsProvider = ::modelMappings,
        defaultHeaders = defaultHeaders(),
        httpClient = httpClient,
        requestLogger = requestLogger,
    )
    private val messagesDelegate = PassThroughSubscriptionProxyProvider(
        id = ID,
        displayName = DISPLAY_NAME,
        litellmProvider = LITELLM_PROVIDER,
        baseUri = anthropicCompatibleBaseUri,
        accessTokenProvider = ::accessToken,
        tokenRefresher = ::refreshAfterUnauthorized,
        modelMappingsProvider = ::modelMappings,
        defaultHeaders = defaultHeaders(),
        httpClient = httpClient,
        requestLogger = requestLogger,
    )

    override val id: String = ID
    override val displayName: String = DISPLAY_NAME

    override fun isConfigured(): Boolean = credentialsProvider()?.isUsable() == true

    override fun models() = chatDelegate.models()

    override suspend fun handle(ctx: de.moritzf.proxy.server.ProxyCall, request: SubscriptionProxyRequest) {
        when (request.route) {
            SubscriptionProxyRoute.ANTHROPIC_MESSAGES -> messagesDelegate.handle(ctx, request)
            SubscriptionProxyRoute.CHAT_COMPLETIONS,
            SubscriptionProxyRoute.RESPONSES -> chatDelegate.handle(ctx, request)
        }
    }

    private fun accessToken(): String? {
        val credentials = credentialsProvider() ?: return null
        val refreshed = runCatching { credentialRefresher.refreshIfNeeded(credentials) }.getOrDefault(credentials)
        if (refreshed != credentials) credentialsSaver(refreshed)
        return refreshed.accessToken.trim().takeIf { it.isNotBlank() }
    }

    private fun refreshAfterUnauthorized(@Suppress("UNUSED_PARAMETER") staleAccessToken: String?): String? {
        val credentials = credentialsProvider() ?: return null
        val refreshed = runCatching { credentialRefresher.refresh(credentials) }.getOrNull() ?: return null
        credentialsSaver(refreshed)
        return refreshed.accessToken.trim().takeIf { it.isNotBlank() }
    }

    private fun modelMappings(): List<PassThroughSubscriptionProxyProvider.ModelMapping> {
        return listOf(
            PassThroughSubscriptionProxyProvider.ModelMapping(
                localId = MODEL_ID,
                upstreamId = MODEL_ID,
                supportedRoutes = setOf(SubscriptionProxyRoute.CHAT_COMPLETIONS, SubscriptionProxyRoute.ANTHROPIC_MESSAGES),
                supportsPromptCaching = true,
                maxInputTokens = 262_144,
                maxOutputTokens = 65_536,
                isDefault = true,
            ),
        )
    }

    companion object {
        const val ID = "kimi"
        const val MODEL_ID = "kimi-for-coding"
        private const val DISPLAY_NAME = "Kimi Code"
        private const val LITELLM_PROVIDER = "kimi"
        private val OPENAI_COMPATIBLE_BASE_URI = URI.create("https://api.kimi.com/coding/v1")
        private val ANTHROPIC_COMPATIBLE_BASE_URI = URI.create("https://api.kimi.com/coding")
        private val DEFAULT_REQUEST_LOG_DIR = System.getProperty("java.io.tmpdir") +
            "/openai-usage-quota-intellij/subscription-proxy-kimi-requests"

        private fun defaultHeaders(): Map<String, String> = mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "User-Agent" to "KimiCLI/1.40.0",
        ) + KimiDeviceHeaders.all()
    }
}
