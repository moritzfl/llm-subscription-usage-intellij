package de.moritzf.quota.opencode.proxy

import de.moritzf.proxy.logging.RequestLogger
import de.moritzf.proxy.model.CodexInstructionsProvider
import de.moritzf.proxy.server.ChatCompletionsHandler
import de.moritzf.proxy.server.JsonHelper
import de.moritzf.proxy.server.ProxyCall
import de.moritzf.proxy.subscription.PassThroughSubscriptionProxyProvider
import de.moritzf.proxy.subscription.SubscriptionProxyRequest
import de.moritzf.proxy.subscription.SubscriptionProxyRoute
import de.moritzf.proxy.usage.UsageTracker
import de.moritzf.quota.github.proxy.GitHubCopilotClaudeChatBridge
import de.moritzf.quota.opencode.OpenCodeQuotaClient
import de.moritzf.quota.opencode.OpenCodeQuotaException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** A request-scoped login. Refresh stays pinned to its account and organization. */
class OpenCodeConsoleSession(
    val accountId: String,
    var accessToken: String,
    val organizationId: String?,
    private val tokenRefresher: (String) -> String?,
) {
    fun refresh(staleToken: String): String? = tokenRefresher(staleToken)?.also { accessToken = it }
}

internal class OpenCodeConsoleProxy(
    private val httpClient: HttpClient,
    private val requestLogger: RequestLogger,
    private val endpoint: URI = OpenCodeQuotaClient.DEFAULT_ENDPOINT,
    private val pools: () -> OpenCodePools = { OpenCodePools() },
) {
    private data class Catalog(
        val accountId: String,
        val organizationId: String?,
        val accessToken: String,
        val fetchedAt: Long,
        val models: List<OpenCodeConsoleModel>,
    )

    private var catalog: Catalog? = null
    private val anthropicBridge = GitHubCopilotClaudeChatBridge(bridgeModel = { true })

    @Synchronized
    fun models(session: OpenCodeConsoleSession): List<OpenCodeConsoleModel> {
        val cached = catalog
        if (cached != null && cached.accountId == session.accountId && cached.organizationId == session.organizationId &&
            cached.accessToken == session.accessToken && System.currentTimeMillis() - cached.fetchedAt < 60_000
        ) return cached.models
        var response = fetchConfig(session)
        if (response.statusCode() == 401 && session.refresh(session.accessToken) != null) response = fetchConfig(session)
        if (response.statusCode() !in 200..299) {
            throw OpenCodeQuotaException("OpenCode inference configuration failed: HTTP ${response.statusCode()}", response.statusCode())
        }
        val models = try {
            OpenCodeConsoleModel.parse(response.body(), pools())
        } catch (_: Exception) {
            throw OpenCodeQuotaException("Could not parse OpenCode inference configuration", 200)
        }
        catalog = Catalog(session.accountId, session.organizationId, session.accessToken, System.currentTimeMillis(), models)
        return models
    }

    private fun fetchConfig(session: OpenCodeConsoleSession): HttpResponse<String> {
        val request = HttpRequest.newBuilder(endpoint.resolve("api/v2/config"))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("Accept", "application/json")
            .apply { session.organizationId?.let { header("x-org-id", it) } }
            .GET().build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    suspend fun handle(ctx: ProxyCall, request: SubscriptionProxyRequest, session: OpenCodeConsoleSession) {
        val configured = models(session).find { it.model.localId == request.model.localId }
        if (configured == null || request.route !in configured.model.supportedRoutes) {
            JsonHelper.toErrorResponse(ctx, "OpenCode model is no longer available for this organization.", 400, "invalid_request_error")
            return
        }
        val scoped = request.copy(model = configured.model)
        val clientHeaders = OpenCodeRequestHeaders.forRequest(ctx.header(OpenCodeRequestHeaders.SESSION), request.body)
        if (request.route == SubscriptionProxyRoute.CHAT_COMPLETIONS && configured.nativeRoute == SubscriptionProxyRoute.RESPONSES) {
            val handler = ChatCompletionsHandler(
                requestLogger = requestLogger,
                usageTracker = UsageTracker(),
                responsesRequester = ChatCompletionsHandler.ResponsesRequester { payload, requestId, _ ->
                    fun send(): HttpResponse<java.io.InputStream> {
                        val headers = configured.headers + clientHeaders + mapOf(
                            "Authorization" to "Bearer ${session.accessToken}",
                            "Content-Type" to "application/json",
                        )
                        val builder = HttpRequest.newBuilder(configured.targetUri).timeout(Duration.ofMinutes(15))
                        headers.forEach { (name, value) -> builder.setHeader(name, value) }
                        requestLogger.logUpstreamRequest(requestId, "POST", "/responses", headers, payload)
                        return httpClient.send(builder.POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofInputStream())
                    }
                    var response = send()
                    if (response.statusCode() == 401) {
                        val token = session.refresh(session.accessToken)
                        if (token != null) {
                            response.body().close()
                            response = send()
                        }
                    }
                    response
                },
                store = false,
                configuredModels = null,
                fullRequestLogging = false,
                forwardPromptCacheHeaders = false,
                instructionsProvider = CodexInstructionsProvider("You are a helpful assistant."),
                responsesBodyTransformer = { body ->
                    configured.body.forEach { (key, value) -> if (!body.has(key)) body.set(key, value) }
                },
            )
            handler.handleParsed(ctx, request.requestId, JsonObject(request.body + ("model" to JsonPrimitive(configured.model.upstreamId))))
            return
        }
        val bridge = request.route == SubscriptionProxyRoute.CHAT_COMPLETIONS && configured.nativeRoute == SubscriptionProxyRoute.ANTHROPIC_MESSAGES
        val headers = if (configured.nativeRoute == SubscriptionProxyRoute.ANTHROPIC_MESSAGES) {
            mapOf("anthropic-version" to "2023-06-01") + configured.headers
        } else configured.headers
        val delegate = PassThroughSubscriptionProxyProvider(
            id = OpenCodeZenSubscriptionProxyProvider.ID,
            displayName = "OpenCode Zen",
            litellmProvider = "opencode",
            baseUri = configured.baseUri,
            accessTokenProvider = { session.accessToken },
            tokenRefresher = { stale -> stale?.let(session::refresh) },
            modelMappingsProvider = { emptyList() },
            defaultHeaders = headers,
            requestHeadersProvider = { clientHeaders },
            upstreamUrlProvider = { configured.targetUri.toString() },
            requestBodyTransformer = { req, body ->
                val transformed = if (bridge) anthropicBridge.openAiChatToAnthropicMessagesBody(req, body) else body
                JsonObject(configured.body + transformed)
            },
            jsonResponseTransformer = { req, body -> if (bridge) anthropicBridge.anthropicMessageToOpenAiChat(req, body) else body },
            sseLineTransformer = if (bridge) anthropicBridge::openAiChatSseLine else null,
            sseStreamComplete = { anthropicBridge.clearStreamState(it.requestId) },
            httpClient = httpClient,
            requestLogger = requestLogger,
        )
        delegate.handle(ctx, scoped)
    }
}
