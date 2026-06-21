package de.moritzf.quota.openai.proxy

import de.moritzf.proxy.auth.CredentialsProvider
import de.moritzf.proxy.config.ServerConfig
import de.moritzf.proxy.logging.RequestLogger
import de.moritzf.proxy.model.CodexClientVersionResolver
import de.moritzf.proxy.model.CodexInstructionsProvider
import de.moritzf.proxy.server.ChatCompletionsHandler
import de.moritzf.proxy.server.JsonHelper
import de.moritzf.proxy.server.ProxyCall
import de.moritzf.proxy.server.ResponsesHandler
import de.moritzf.proxy.subscription.SubscriptionProxyModel
import de.moritzf.proxy.subscription.SubscriptionProxyProvider
import de.moritzf.proxy.subscription.SubscriptionProxyRequest
import de.moritzf.proxy.subscription.SubscriptionProxyRoute
import de.moritzf.proxy.transport.CodexHttpClient
import de.moritzf.proxy.usage.UsageTracker
import de.moritzf.quota.shared.JsonSupport
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class OpenAiCodexSubscriptionProxyProvider(
    private val accessTokenProvider: () -> String?,
    private val accountIdProvider: () -> String?,
    private val tokenRefresher: (staleAccessToken: String?) -> String? = { null },
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build(),
    private val upstreamBaseUri: URI = OpenAiProxyServer.DEFAULT_UPSTREAM_BASE_URI,
    private val fullRequestLogging: Boolean = false,
    private val requestLogDir: String = DEFAULT_REQUEST_LOG_DIR,
    private val codexVersionProvider: () -> String = { CodexClientVersionResolver.resolve(null) },
) : SubscriptionProxyProvider {
    override val id: String = "openai"
    override val displayName: String = "OpenAI/Codex"

    private val config = ServerConfig(
        ServerConfig.DEFAULT_HOST,
        1,
        OpenAiProxyServer.advertisedModels(),
        null,
        upstreamBaseUri.toString(),
        ServerConfig.DEFAULT_CLIENT_ID,
        null,
        null,
        DEFAULT_CODEX_INSTRUCTIONS,
        false,
        emptyMap(),
        null,
        false,
        emptyList(),
        fullRequestLogging,
        requestLogDir,
        false,
        ServerConfig.DEFAULT_CODEX_INSTRUCTIONS_MODE,
        null,
        false,
        false,
    )
    private val requestLogger = RequestLogger(fullRequestLogging, Path.of(requestLogDir))
    private val credentialsProvider = QuotaCodexCredentialsProvider(
        accessTokenProvider,
        accountIdProvider,
        tokenRefresher,
        codexVersionProvider,
    )
    private val client = UnifiedCodexHttpClient(config, httpClient, credentialsProvider)
    private val instructionsProvider = CodexInstructionsProvider(DEFAULT_CODEX_INSTRUCTIONS)
    private val usageTracker = UsageTracker()
    private val chatHandler = ChatCompletionsHandler(client, config, usageTracker, requestLogger, instructionsProvider)
    private val responsesHandler = ResponsesHandler(client, config, usageTracker, requestLogger, instructionsProvider)

    override fun isConfigured(): Boolean = accessTokenProvider().trimmedOrNull() != null

    override fun models(): List<SubscriptionProxyModel> {
        if (!isConfigured()) return emptyList()
        return OpenAiProxyServer.advertisedModels().map { id ->
            SubscriptionProxyModel(
                localId = id,
                upstreamId = id,
                providerId = this.id,
                providerName = displayName,
                litellmProvider = LITELLM_PROVIDER,
                supportedRoutes = setOf(SubscriptionProxyRoute.CHAT_COMPLETIONS, SubscriptionProxyRoute.RESPONSES),
                supportsFunctionCalling = true,
                supportsParallelFunctionCalling = false,
                supportsToolChoice = true,
                supportsVision = true,
                supportsPromptCaching = true,
                maxInputTokens = 272_000,
                maxOutputTokens = 128_000,
            )
        }
    }

    override suspend fun handle(ctx: ProxyCall, request: SubscriptionProxyRequest) {
        when (request.route) {
            SubscriptionProxyRoute.CHAT_COMPLETIONS -> chatHandler.handleParsed(ctx, request.requestId, request.body)
            SubscriptionProxyRoute.RESPONSES -> responsesHandler.handleParsed(ctx, request.requestId, request.body)
            SubscriptionProxyRoute.ANTHROPIC_MESSAGES -> JsonHelper.toErrorResponse(
                ctx,
                "OpenAI/Codex does not support /v1/messages.",
                400,
                "invalid_request_error",
            )
        }
    }

    private class UnifiedCodexHttpClient(
        config: ServerConfig,
        httpClient: HttpClient,
        credentialsProvider: CredentialsProvider,
    ) : CodexHttpClient(config, httpClient, credentialsProvider) {
        @Throws(Exception::class)
        override fun request(
            path: String,
            method: String?,
            body: String?,
            extraHeaders: Map<String, String>?,
        ): HttpResponse<InputStream> {
            return super.request(path, method, sanitizeResponsesBody(path, body), extraHeaders)
        }

        @Throws(Exception::class)
        override fun request(
            path: String,
            method: String?,
            body: String?,
            extraHeaders: Map<String, String>?,
            requestId: String?,
            promptCacheKey: String?,
        ): HttpResponse<InputStream> {
            return super.request(path, method, sanitizeResponsesBody(path, body), extraHeaders, requestId, promptCacheKey)
        }

        @Throws(Exception::class)
        override fun requestString(
            path: String,
            method: String?,
            body: String?,
            extraHeaders: Map<String, String>?,
        ): HttpResponse<String> {
            return super.requestString(path, method, sanitizeResponsesBody(path, body), extraHeaders)
        }

        private fun sanitizeResponsesBody(path: String, body: String?): String? {
            if (path.substringBefore('?') != "/responses" || body.isNullOrBlank()) {
                return body
            }
            return runCatching {
                val root = JsonSupport.json.parseToJsonElement(body).jsonObject
                val sanitized = buildJsonObject {
                    root.forEach { (key, value) ->
                        if (key !in CODEX_OVERRIDDEN_RESPONSE_FIELDS && key !in CODEX_UNSUPPORTED_RESPONSE_FIELDS) {
                            put(key, value)
                        }
                    }
                    put("store", false)
                    put("stream", true)
                }
                JsonSupport.json.encodeToString(JsonObject.serializer(), sanitized)
            }.getOrElse { body }
        }
    }

    companion object {
        private const val DEFAULT_CODEX_INSTRUCTIONS = "You are a coding assistant."
        private const val LITELLM_PROVIDER = "openai-codex"
        private val DEFAULT_REQUEST_LOG_DIR = System.getProperty("java.io.tmpdir") +
            "/openai-usage-quota-intellij/subscription-proxy-openai-requests"
        private val CODEX_OVERRIDDEN_RESPONSE_FIELDS = setOf("store", "stream")
        private val CODEX_UNSUPPORTED_RESPONSE_FIELDS = setOf(
            "max_output_tokens",
            "temperature",
        )

        private fun String?.trimmedOrNull(): String? = this?.trim()?.takeIf { it.isNotBlank() }
    }
}
