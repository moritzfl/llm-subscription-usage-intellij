package de.moritzf.quota.azure.proxy

import de.moritzf.proxy.logging.RequestLogger
import de.moritzf.proxy.subscription.PassThroughSubscriptionProxyProvider
import de.moritzf.proxy.subscription.SubscriptionProxyModel
import de.moritzf.proxy.subscription.SubscriptionProxyProvider
import de.moritzf.proxy.subscription.SubscriptionProxyRequest
import de.moritzf.proxy.subscription.SubscriptionProxyRoute
import de.moritzf.quota.azure.AZURE_DEPLOYMENT_NAME
import de.moritzf.quota.azure.AzureAccountConfig
import de.moritzf.quota.azure.AzureCli
import de.moritzf.quota.azure.AzureLiveUsage
import de.moritzf.quota.azure.AzureModelCatalog
import de.moritzf.quota.azure.AzureQuotaClient
import de.moritzf.quota.azure.azureInferenceTarget
import de.moritzf.quota.azure.azureScopeForUrl
import de.moritzf.quota.azure.azureUpstreamUrl
import de.moritzf.quota.azure.parseRateLimitHeaders
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * OpenAI v1 proxy for one Azure CLI account. Token comes from `az account get-access-token`,
 * matching OpenCode: bearer auth, Cognitive Services scope, Foundry scope on `*.services.ai.azure.com`.
 * A missing model list does not block a named deployment.
 */
internal class AzureSubscriptionProxyProvider(
    private val configProvider: () -> AzureProxyConfig?,
    private val cliFactory: (Path) -> AzureCli = { AzureCli(it) },
    private val accountKey: String = "azure",
    fullRequestLogging: Boolean = false,
    requestLogDir: String = "logs",
    httpClient: HttpClient = defaultClient(),
) : SubscriptionProxyProvider {
    private val clis = java.util.concurrent.ConcurrentHashMap<Path, AzureCli>()
    private val tap = HeaderTapHttpClient(httpClient) { headers, model ->
        val key = configProvider()?.accountId ?: accountKey
        parseRateLimitHeaders(headers, model)?.let { AzureLiveUsage.record(key, it) }
    }
    private val delegate = PassThroughSubscriptionProxyProvider(
        id = ID,
        displayName = DISPLAY_NAME,
        litellmProvider = "azure",
        baseUri = java.net.URI.create("https://azure.invalid"),
        accessTokenProvider = { tokenOrNull() },
        tokenRefresher = { tokenOrNull(force = true) },
        modelMappingsProvider = { modelMappings() },
        upstreamUrlProvider = { request -> upstreamUrl(request) },
        httpClient = tap,
        requestLogger = RequestLogger(fullRequestLogging, Path.of(requestLogDir)),
    )

    override val id: String = ID
    override val displayName: String = DISPLAY_NAME

    override fun isConfigured(): Boolean = configProvider()?.target != null && configProvider()?.executable != null

    override fun models(): List<SubscriptionProxyModel> {
        if (!isConfigured()) return emptyList()
        return modelMappings().map { mapping ->
            SubscriptionProxyModel(
                localId = mapping.localId,
                upstreamId = mapping.upstreamId,
                providerId = ID,
                providerName = DISPLAY_NAME,
                litellmProvider = "azure",
                supportedRoutes = mapping.supportedRoutes,
                supportsFunctionCalling = true,
                supportsToolChoice = true,
                supportsVision = true,
                isDefault = mapping.isDefault,
            )
        }
    }

    override fun fallbackModel(localId: String, route: SubscriptionProxyRoute): SubscriptionProxyModel? {
        if (!isConfigured() || route !in ROUTES) return null
        val upstream = localId.removePrefix(PREFIX).takeIf { AZURE_DEPLOYMENT_NAME.matches(it) } ?: return null
        return model(localId, upstream)
    }

    override suspend fun handle(ctx: de.moritzf.proxy.server.ProxyCall, request: SubscriptionProxyRequest) {
        tap.model = request.model.upstreamId
        delegate.handle(ctx, request)
    }

    private fun tokenOrNull(force: Boolean = false): String? {
        val config = configProvider() ?: return null
        val executable = config.executable ?: return null
        val target = config.target ?: return null
        val cli = clis.getOrPut(executable) { cliFactory(executable) }
        if (force) cli.invalidate()
        return runCatching {
            cli.accessToken(azureScopeForUrl(target.baseUrl), config.account.subscriptionId).accessToken
        }.getOrNull()
    }

    private fun upstreamUrl(request: SubscriptionProxyRequest): String? {
        val target = configProvider()?.target ?: return null
        return azureUpstreamUrl(target.baseUrl, request.route.upstreamPath)
    }

    private fun modelMappings(): List<PassThroughSubscriptionProxyProvider.ModelMapping> {
        val config = configProvider() ?: return emptyList()
        val key = AzureQuotaClient.catalogKey(config.account.subscriptionId, config.account)
        val ids = (AzureModelCatalog.read(key) + config.account.deploymentNames).distinct()
        if (ids.isEmpty()) return emptyList()
        val defaultId = ids.maxOrNull()
        return ids.map { id ->
            PassThroughSubscriptionProxyProvider.ModelMapping(
                localId = "$PREFIX$id",
                upstreamId = id,
                supportedRoutes = ROUTES,
                isDefault = id == defaultId,
            )
        }
    }

    private fun model(localId: String, upstreamId: String): SubscriptionProxyModel {
        return SubscriptionProxyModel(
            localId = localId,
            upstreamId = upstreamId,
            providerId = ID,
            providerName = DISPLAY_NAME,
            litellmProvider = "azure",
            supportedRoutes = ROUTES,
            supportsFunctionCalling = true,
            supportsToolChoice = true,
            supportsVision = true,
        )
    }

    data class AzureProxyConfig(
        val accountId: String = "azure",
        val executable: Path?,
        val account: AzureAccountConfig,
    ) {
        val target = azureInferenceTarget(account)
    }

    companion object {
        const val ID = "azure"
        const val DISPLAY_NAME = "Azure"
        const val PREFIX = "az-"
        val ROUTES: Set<SubscriptionProxyRoute> = setOf(
            SubscriptionProxyRoute.CHAT_COMPLETIONS,
            SubscriptionProxyRoute.RESPONSES,
            SubscriptionProxyRoute.COMPLETIONS,
        )

        private fun defaultClient(): HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }
}

private class HeaderTapHttpClient(
    private val delegate: HttpClient,
    private val onHeaders: (Map<String, List<String>>, String?) -> Unit,
) : HttpClient() {
    @Volatile var model: String? = null

    override fun cookieHandler() = delegate.cookieHandler()
    override fun connectTimeout() = delegate.connectTimeout()
    override fun followRedirects() = delegate.followRedirects()
    override fun proxy() = delegate.proxy()
    override fun sslContext() = delegate.sslContext()
    override fun sslParameters() = delegate.sslParameters()
    override fun authenticator() = delegate.authenticator()
    override fun version() = delegate.version()
    override fun executor() = delegate.executor()

    override fun <T> send(request: HttpRequest, responseBodyHandler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
        val response = delegate.send(request, responseBodyHandler)
        onHeaders(response.headers().map(), model)
        return response
    }

    override fun <T> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
    ): CompletableFuture<HttpResponse<T>> = delegate.sendAsync(request, responseBodyHandler).whenComplete { response, _ ->
        if (response != null) onHeaders(response.headers().map(), model)
    }

    override fun <T> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
        pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?,
    ): CompletableFuture<HttpResponse<T>> =
        delegate.sendAsync(request, responseBodyHandler, pushPromiseHandler).whenComplete { response, _ ->
            if (response != null) onHeaders(response.headers().map(), model)
        }
}
