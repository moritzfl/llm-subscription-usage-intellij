package de.moritzf.quota.opencode.proxy

import de.moritzf.proxy.server.JsonHelper
import de.moritzf.proxy.server.ProxyCall
import de.moritzf.proxy.subscription.SubscriptionProxyModel
import de.moritzf.proxy.subscription.SubscriptionProxyProvider
import de.moritzf.proxy.subscription.SubscriptionProxyRequest
import de.moritzf.proxy.subscription.SubscriptionProxyRoute
import de.moritzf.proxy.logging.RequestLogger
import de.moritzf.quota.opencode.OpenCodeQuotaClient
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Path

class OpenCodeZenSubscriptionProxyProvider internal constructor(
    private val consoleSessionProvider: () -> OpenCodeConsoleSession?,
    httpClient: HttpClient = HttpClient.newHttpClient(),
    fullRequestLogging: Boolean = false,
    requestLogDir: String = DEFAULT_REQUEST_LOG_DIR,
    consoleEndpoint: URI = OpenCodeQuotaClient.DEFAULT_ENDPOINT,
    pools: () -> OpenCodePools = { OpenCodePools() },
) : SubscriptionProxyProvider {
    private val console = OpenCodeConsoleProxy(
        httpClient,
        RequestLogger(fullRequestLogging, Path.of(requestLogDir)),
        consoleEndpoint,
        pools,
    )

    override val id: String = ID
    override val displayName: String = DISPLAY_NAME

    override fun isConfigured(): Boolean = runCatching { consoleSessionProvider() != null }.getOrDefault(false)

    override fun models() = runCatching {
        consoleSessionProvider()?.let { session -> console.models(session).map { it.model } }.orEmpty()
    }.getOrDefault(emptyList())

    override fun fallbackModel(localId: String, route: SubscriptionProxyRoute): SubscriptionProxyModel? {
        if (!localId.startsWith(PREFIX) || localId.startsWith(GO_PREFIX) || localId.startsWith(ZEN_PREFIX)) return null
        val bare = localId.removePrefix(PREFIX)
        val models = models()
        return models.firstOrNull { it.localId == "$GO_PREFIX$bare" && route in it.supportedRoutes }
            ?: models.firstOrNull { it.localId == "$ZEN_PREFIX$bare" && route in it.supportedRoutes }
    }

    override suspend fun handle(ctx: ProxyCall, request: SubscriptionProxyRequest) {
        val session = consoleSessionProvider()
        if (session == null) {
            JsonHelper.toErrorResponse(ctx, "OpenCode Console login required.", 401, "authentication_error")
            return
        }
        console.handle(ctx, request, session)
    }

    companion object {
        const val ID = "opencode"
        const val PREFIX = "oc-"
        const val GO_PREFIX = "oc-go-"
        const val ZEN_PREFIX = "oc-zen-"
        private const val DISPLAY_NAME = "OpenCode"
        private val DEFAULT_REQUEST_LOG_DIR = System.getProperty("java.io.tmpdir") +
            "/openai-usage-quota-intellij/subscription-proxy-opencode-requests"
    }
}
