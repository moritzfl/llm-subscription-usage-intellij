package de.moritzf.quota.azure.proxy

import de.moritzf.proxy.logging.RequestLogger
import de.moritzf.proxy.server.JsonHelper
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
import java.nio.file.Path
import java.time.Duration
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

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
    private val httpClient: HttpClient = defaultClient(),
) : SubscriptionProxyProvider {
    private val requestLogger = RequestLogger(fullRequestLogging, Path.of(requestLogDir))

    override val id: String = ID
    override val displayName: String = DISPLAY_NAME

    override fun isConfigured(): Boolean = configProvider()?.let { it.target != null && it.executable != null } == true

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
        if (!isConfigured() || route !in ROUTES || !localId.startsWith(PREFIX)) return null
        val upstream = localId.removePrefix(PREFIX)
            .takeIf { AZURE_DEPLOYMENT_NAME.matches(it) && isChatDeployment(it) } ?: return null
        return model(localId, upstream)
    }

    override suspend fun handle(ctx: de.moritzf.proxy.server.ProxyCall, request: SubscriptionProxyRequest) {
        // Settings/failover can change while a request is in flight. Bind token, URL and
        // response counters to the same account for the entire request, including a 401 retry.
        val config = configProvider()
        val target = config?.target
        val executable = config?.executable
        if (target == null || executable == null) {
            JsonHelper.toErrorResponse(ctx, "Azure endpoint and CLI are required.", 401, "authentication_error")
            return
        }
        val cli = cliFactory(executable)
        fun tokenOrNull(): String? = runCatching {
            cli.accessToken(azureScopeForUrl(target.baseUrl), config.account.subscriptionId).accessToken
        }.getOrNull()
        val key = AzureLiveUsage.key(config.accountId.ifBlank { accountKey }, config.account)
        PassThroughSubscriptionProxyProvider(
            id = ID,
            displayName = DISPLAY_NAME,
            litellmProvider = "azure",
            baseUri = java.net.URI.create(target.baseUrl),
            accessTokenProvider = ::tokenOrNull,
            tokenRefresher = { tokenOrNull() },
            modelMappingsProvider = { emptyList() },
            upstreamUrlProvider = { azureUpstreamUrl(target.baseUrl, it.route.upstreamPath) },
            requestBodyTransformer = { req, body -> adaptChatRequest(req, body) },
            responseHeadersObserver = { req, headers ->
                parseRateLimitHeaders(headers, req.model.upstreamId)?.let { AzureLiveUsage.record(key, it) }
            },
            httpClient = httpClient,
            requestLogger = requestLogger,
        ).handle(ctx, request)
    }

    private fun modelMappings(): List<PassThroughSubscriptionProxyProvider.ModelMapping> {
        val config = configProvider() ?: return emptyList()
        val key = AzureQuotaClient.catalogKey(config.account.subscriptionId, config.account)
        val ids = (AzureModelCatalog.read(key) + config.account.deploymentNames).distinct().filter(::isChatDeployment)
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

    private fun adaptChatRequest(request: SubscriptionProxyRequest, body: JsonObject): JsonObject {
        if (request.route != SubscriptionProxyRoute.CHAT_COMPLETIONS) return body
        val id = request.model.upstreamId.lowercase()
        val reasoning = REASONING_MODEL.matches(id)
        val mistral = id.startsWith("mistral-")
        if (!reasoning && !mistral) return body
        val reasoningTools = reasoning &&
            listOf("tools", "functions").any { (body[it] as? JsonArray)?.isNotEmpty() == true }
        return buildJsonObject {
            body.forEach { (key, value) ->
                when {
                    reasoning && key == "max_tokens" -> {
                        if ("max_completion_tokens" !in body) put("max_completion_tokens", value)
                    }
                    reasoning && key in setOf("stop", "temperature", "top_p") -> Unit
                    mistral && key == "max_completion_tokens" -> {
                        if ("max_tokens" !in body) put("max_tokens", value)
                    }
                    mistral && key == "user" -> Unit
                    else -> put(key, value)
                }
            }
            // Azure GPT 5/6 chat rejects function tools with reasoning (including the
            // default on GPT-6). Their Responses route permits both; chat needs "none".
            if (reasoningTools) put("reasoning_effort", JsonPrimitive("none"))
        }
    }

    private fun isChatDeployment(id: String): Boolean {
        val name = id.lowercase()
        return !name.startsWith("text-embedding-") && !name.startsWith("mistral-ocr-") &&
            !name.startsWith("mistral-document-ai-")
    }

    data class AzureProxyConfig(
        val accountId: String = "azure",
        val executable: Path?,
        val account: AzureAccountConfig,
    ) {
        val target = azureInferenceTarget(account)
    }

    companion object {
        private val REASONING_MODEL = Regex("gpt-[56](?:[.-].*)?")
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
