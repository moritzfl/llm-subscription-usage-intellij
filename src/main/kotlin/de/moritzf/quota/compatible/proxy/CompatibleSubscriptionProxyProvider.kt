package de.moritzf.quota.compatible.proxy

import de.moritzf.proxy.model.KnownReasoningLevels
import de.moritzf.proxy.model.ReasoningCatalogs
import de.moritzf.proxy.server.JsonHelper
import de.moritzf.proxy.server.LiteLlmBudgetError
import de.moritzf.proxy.subscription.OpenAiCompatibleApiKeySubscriptionProxyProvider
import de.moritzf.proxy.subscription.SubscriptionProxyProvider
import de.moritzf.proxy.subscription.SubscriptionProxyRoute
import de.moritzf.quota.compatible.CompatibleEndpoint
import de.moritzf.quota.litellm.LiteLlmClient
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One independently addressed endpoint. An account rename never changes its public model IDs. */
class CompatibleSubscriptionProxyProvider(
    accountId: String,
    name: String,
    private val endpoint: CompatibleEndpoint,
    private val apiKeyProvider: () -> String?,
    private val httpClient: HttpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
    fullRequestLogging: Boolean = false,
    requestLogDir: String =
        System.getProperty("java.io.tmpdir") + "/openai-compatible-proxy-requests",
    remoteReasoningLevels: (String) -> List<String>? = ReasoningCatalogs::levels,
) :
    SubscriptionProxyProvider by OpenAiCompatibleApiKeySubscriptionProxyProvider(
        id = if (endpoint.liteLlm) "litellm" else ID,
        displayName = "${if (endpoint.liteLlm) "LiteLLM" else "OpenAI-compatible"} ($name)",
        litellmProvider = "openai",
        baseUri = endpoint.baseUri,
        apiKeyProvider = apiKeyProvider,
        localIdPrefix = modelPrefix(accountId),
        staticModels =
            endpoint.modelIds.map {
                OpenAiCompatibleApiKeySubscriptionProxyProvider.StaticModel(it)
            },
        discoverModels = endpoint.modelIds.isEmpty(),
        includeModel = { endpoint.modelIds.isEmpty() || it in endpoint.modelIds },
        supportedRoutes =
            if (endpoint.liteLlm)
                setOf(SubscriptionProxyRoute.CHAT_COMPLETIONS, SubscriptionProxyRoute.RESPONSES)
            else setOf(SubscriptionProxyRoute.CHAT_COMPLETIONS),
        // OpenAI's model-list contract does not declare these capabilities. Unknown is not false.
        modelTransformer = {
            it.copy(
                supportsFunctionCalling = null,
                supportsToolChoice = null,
                supportsVision = true.takeIf { _ -> it.id == endpoint.visionModel },
                supportsPdfInput = true.takeIf { _ -> it.id == endpoint.documentModel },
                supportsParallelFunctionCalling = null,
                supportsPromptCaching = null,
                inputCostPerToken = null,
                outputCostPerToken = null,
            )
        },
        httpClient = httpClient,
        modelRequestTimeout = Duration.ofSeconds(10),
        modelListEnricher =
            if (endpoint.liteLlm) LiteLlmClient(endpoint, httpClient)::enrichModels else null,
        // Any model can sit behind a custom endpoint: when it states no levels, match the name
        // against known families, then the OpenRouter and models.dev catalogs.
        reasoningEffortsFallback = { id ->
            KnownReasoningLevels.forModel(id) ?: remoteReasoningLevels(id)
        },
        fullRequestLogging = fullRequestLogging,
        requestLogDir = requestLogDir,
    ) {
    fun discoverModelDetails(): List<OpenAiCompatibleApiKeySubscriptionProxyProvider.StaticModel> =
        OpenAiCompatibleApiKeySubscriptionProxyProvider(
                id = ID,
                displayName = "OpenAI-compatible",
                litellmProvider = "openai",
                baseUri = endpoint.baseUri,
                apiKeyProvider = apiKeyProvider,
                httpClient = httpClient,
                modelRequestTimeout = Duration.ofSeconds(10),
                modelTransformer = {
                    it.copy(
                        supportsVision = null,
                        supportsPdfInput = null,
                        supportsFunctionCalling = null,
                        supportsToolChoice = null,
                        supportsParallelFunctionCalling = null,
                        supportsPromptCaching = null,
                        inputCostPerToken = null,
                        outputCostPerToken = null,
                    )
                },
                modelListEnricher =
                    if (endpoint.liteLlm) LiteLlmClient(endpoint, httpClient)::enrichModels
                    else null,
            )
            .fetchModels(requireKey())
            .sortedBy { it.id }

    fun discoverModels(): List<String> = discoverModelDetails().map { it.id }

    /** An explicit user action: verify inference, even when /models is unavailable. */
    fun testModel(modelId: String): String {
        require(modelId.isNotBlank()) { "Select a model or enter a model ID first." }
        val body = buildJsonObject {
            put("model", modelId)
            put("stream", false)
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", "Reply with only the word OK.")
                        }
                    )
                },
            )
        }
        val response =
            httpClient.send(
                HttpRequest.newBuilder(
                        java.net.URI(endpoint.baseUri.toString() + "/chat/completions")
                    )
                    .timeout(Duration.ofSeconds(45))
                    .header("Authorization", "Bearer ${requireKey()}")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        check(response.statusCode() in 200..299) {
            if (LiteLlmBudgetError.isBudgetExceeded(response.statusCode(), response.body())) {
                return@check BUDGET_EXCEEDED.format(response.statusCode())
            }
            when (response.statusCode()) {
                401,
                403 ->
                    "Access denied (HTTP ${response.statusCode()}). Check the API key and model access."
                404 ->
                    "Chat endpoint or model not found (HTTP 404). Check the base URL and model ID."
                429 ->
                    "The provider rejected the request because of its usage or rate limit (HTTP 429)."
                else ->
                    "Model test failed (HTTP ${response.statusCode()}). Check that this model supports Chat Completions."
            }
        }
        val root = JsonHelper.parseToJsonElementOrNull(response.body())
        val content = runCatching {
            root
                ?.jsonObject
                ?.get("choices")
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("message")
                ?.jsonObject
                ?.get("content")
                ?.jsonPrimitive
                ?.contentOrNull
        }
            .getOrNull()
        check(!content.isNullOrBlank()) {
            "The endpoint responded, but returned no chat text. Check the model ID and API compatibility."
        }
        return content.take(200)
    }

    private fun requireKey(): String =
        apiKeyProvider()?.trim()?.takeIf(String::isNotEmpty) ?: error("Enter an API key.")

    companion object {
        const val ID = "openai-compatible"

        fun modelPrefix(accountId: String): String = "api-$accountId/"

        private const val BUDGET_EXCEEDED =
            "The key budget is exhausted (HTTP %d). Raise the budget or wait until it resets."
    }
}
