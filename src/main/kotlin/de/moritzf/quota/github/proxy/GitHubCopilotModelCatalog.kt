package de.moritzf.quota.github.proxy

import de.moritzf.proxy.server.JsonHelper
import de.moritzf.proxy.subscription.SubscriptionProxyRoute
import de.moritzf.proxy.transport.UrlResolver
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal data class GitHubCopilotRemoteModel(
    val id: String,
    val supportedRoutes: Set<SubscriptionProxyRoute>,
    val supportsFunctionCalling: Boolean,
    val supportsVision: Boolean,
    val supportsPdf: Boolean = false,
    val pdfCapabilityKnown: Boolean = false,
    val maxInputTokens: Int?,
    val maxOutputTokens: Int?,
    val isDefault: Boolean,
)

@OptIn(ExperimentalTime::class)
internal class GitHubCopilotModelCatalog(
    private val accessTokenProvider: () -> String?,
    private val httpClient: HttpClient,
    private val upstreamBaseUri: URI,
    private val persistentModelCacheProvider: () -> String?,
    private val persistentModelCacheSaver: (String?) -> Unit,
    private val missingModelRetryDelays: List<Duration>,
    private val modelCacheTtl: kotlin.time.Duration,
) {
    @Volatile
    private var modelCache: ModelCache? = null
    private val missingModelRetryLock = Any()
    @Volatile
    private var missingModelRetry: MissingModelRetry? = null

    fun models(): List<GitHubCopilotRemoteModel> {
        val now = Clock.System.now()
        val memory = modelCache
        // Warm in-memory cache: avoid remote /models on every request.
        if (memory != null && now - memory.fetchedAt < modelCacheTtl) {
            return memory.models
        }
        // Memory miss or TTL expired: load disk as merge baseline, then revalidate.
        val disk = if (memory == null) loadPersistedModelCache(now) else null
        return refreshRemoteModels(now, memory ?: disk)
    }

    @OptIn(ExperimentalTime::class)
    private fun refreshRemoteModels(now: Instant, cached: ModelCache?): List<GitHubCopilotRemoteModel> {
        val token = accessTokenProvider().trimmedOrNull() ?: return cached?.models.orEmpty()
        val fetched = runCatching { fetchModels(token) }.getOrDefault(emptyList())
        if (fetched.isEmpty()) {
            if (cached != null) modelCache = cached.copy(fetchedAt = now)
            return cached?.models.orEmpty()
        }

        if (cached == null) {
            cacheModels(fetched, now)
            return fetched
        }

        val fetchedIds = fetched.mapTo(mutableSetOf()) { it.id }
        val missingCachedModels = cached.models.filter { it.id !in fetchedIds }
        if (missingCachedModels.isEmpty()) {
            clearMissingModelRetry()
            cacheModels(fetched, now)
            return fetched
        }

        val protectedModels = mergeModels(fetched, missingCachedModels)
        modelCache = ModelCache(protectedModels, now)
        // Keep disk aligned with the protected set until retries finish.
        savePersistedModels(protectedModels, now)
        startMissingModelRetry(token, missingCachedModels, fetched)
        return protectedModels
    }

    @OptIn(ExperimentalTime::class)
    private fun loadPersistedModelCache(now: Instant): ModelCache? {
        val raw = runCatching { persistentModelCacheProvider() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val root = JsonHelper.parseToJsonElementOrNull(raw) as? JsonObject ?: return null
        val models = (root["models"] as? JsonArray)
            ?.mapNotNull { parseCachedRemoteModel(it) }
            ?.filter { it.supportedRoutes.isNotEmpty() }
            ?.distinctBy { it.id }
            .orEmpty()
        if (models.isEmpty()) return null
        val fetchedAt = parseFetchedAt(root) ?: (now - modelCacheTtl)
        return ModelCache(models, fetchedAt)
    }

    @OptIn(ExperimentalTime::class)
    private fun cacheModels(models: List<GitHubCopilotRemoteModel>, fetchedAt: Instant = Clock.System.now()) {
        modelCache = ModelCache(models, fetchedAt)
        savePersistedModels(models, fetchedAt)
    }

    @OptIn(ExperimentalTime::class)
    private fun savePersistedModels(models: List<GitHubCopilotRemoteModel>, fetchedAt: Instant = Clock.System.now()) {
        val payload = buildJsonObject {
            put("version", 1)
            put("fetchedAtEpochMs", fetchedAt.toEpochMilliseconds())
            put(
                "models",
                JsonArray(models.map { model ->
                    buildJsonObject {
                        put("id", model.id)
                        put("supportedRoutes", JsonArray(model.supportedRoutes.map { JsonPrimitive(it.normalizedPath) }))
                        put("supportsFunctionCalling", model.supportsFunctionCalling)
                        put("supportsVision", model.supportsVision)
                        put("supportsPdf", model.supportsPdf)
                        put("pdfCapabilityKnown", model.pdfCapabilityKnown)
                        model.maxInputTokens?.let { put("maxInputTokens", it) }
                        model.maxOutputTokens?.let { put("maxOutputTokens", it) }
                        put("isDefault", model.isDefault)
                    }
                }),
            )
        }
        runCatching { persistentModelCacheSaver(JsonHelper.encodeToString(payload)) }
    }

    @OptIn(ExperimentalTime::class)
    private fun parseFetchedAt(root: JsonObject): Instant? {
        val epochMs = (root["fetchedAtEpochMs"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
        return epochMs?.let(Instant::fromEpochMilliseconds)
    }

    private fun parseCachedRemoteModel(element: JsonElement): GitHubCopilotRemoteModel? {
        val item = element as? JsonObject ?: return null
        val id = stringField(item, "id") ?: return null
        val routes = (item["supportedRoutes"] as? JsonArray)
            ?.mapNotNull { route -> routeForStorageValue((route as? JsonPrimitive)?.contentOrNull) }
            ?.toSet()
            .orEmpty()
        return GitHubCopilotRemoteModel(
            id = id,
            supportedRoutes = routes,
            supportsFunctionCalling = boolField(item, "supportsFunctionCalling") ?: true,
            supportsVision = boolField(item, "supportsVision") ?: false,
            supportsPdf = boolField(item, "supportsPdf") ?: false,
            pdfCapabilityKnown = boolField(item, "pdfCapabilityKnown") ?: false,
            maxInputTokens = intField(item, "maxInputTokens"),
            maxOutputTokens = intField(item, "maxOutputTokens"),
            isDefault = boolField(item, "isDefault") ?: false,
        )
    }

    private fun mergeModels(fetched: List<GitHubCopilotRemoteModel>, cachedModels: List<GitHubCopilotRemoteModel>): List<GitHubCopilotRemoteModel> {
        val merged = LinkedHashMap<String, GitHubCopilotRemoteModel>()
        fetched.forEach { model -> merged[model.id] = model }
        cachedModels.forEach { model -> merged.putIfAbsent(model.id, model) }
        return merged.values.toList()
    }

    @OptIn(ExperimentalTime::class)
    private fun startMissingModelRetry(token: String, missingModels: List<GitHubCopilotRemoteModel>, firstFetched: List<GitHubCopilotRemoteModel>) {
        val missingIds = missingModels.mapTo(mutableSetOf()) { it.id }
        val retry = synchronized(missingModelRetryLock) {
            val active = missingModelRetry
            if (active != null && active.missingIds == missingIds) return
            val next = MissingModelRetry(missingIds, GitHubCopilotProxyIds.MODEL_RETRY_SEQUENCE.incrementAndGet())
            missingModelRetry = next
            next
        }
        Thread {
            retryMissingModels(token, retry, missingModels, firstFetched)
        }.apply {
            isDaemon = true
            name = "github-copilot-model-retry-${retry.sequence}"
            start()
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun retryMissingModels(
        token: String,
        retry: MissingModelRetry,
        missingModels: List<GitHubCopilotRemoteModel>,
        firstFetched: List<GitHubCopilotRemoteModel>,
    ) {
        val stillMissingIds = retry.missingIds.toMutableSet()
        val confirmedModels = LinkedHashMap<String, GitHubCopilotRemoteModel>()
        var successfulRetries = 0
        var latestFetched = firstFetched
        for (delay in missingModelRetryDelays) {
            if (!isActiveRetry(retry)) return
            Thread.sleep(delay.toMillis())
            if (!isActiveRetry(retry)) return
            val fetched = runCatching { fetchModels(token) }.getOrDefault(emptyList())
            if (fetched.isEmpty()) continue
            successfulRetries++
            latestFetched = fetched
            fetched.forEach { model ->
                if (model.id in stillMissingIds) {
                    stillMissingIds.remove(model.id)
                    confirmedModels[model.id] = model
                }
            }
            if (stillMissingIds.isEmpty()) {
                cacheModels(mergeModels(fetched, confirmedModels.values.toList()))
                finishMissingModelRetry(retry)
                return
            }
        }
        if (isActiveRetry(retry)) {
            val cachedConfirmedModels = missingModels.filter { it.id !in stillMissingIds && it.id !in confirmedModels }
            val unconfirmedMissingModels = if (successfulRetries < missingModelRetryDelays.size) {
                missingModels.filter { it.id in stillMissingIds }
            } else {
                emptyList()
            }
            cacheModels(
                mergeModels(latestFetched, confirmedModels.values.toList() + cachedConfirmedModels + unconfirmedMissingModels),
            )
            finishMissingModelRetry(retry)
        }
    }

    private fun isActiveRetry(retry: MissingModelRetry): Boolean = missingModelRetry === retry

    private fun clearMissingModelRetry() {
        synchronized(missingModelRetryLock) {
            missingModelRetry = null
        }
    }

    private fun finishMissingModelRetry(retry: MissingModelRetry) {
        synchronized(missingModelRetryLock) {
            if (missingModelRetry === retry) missingModelRetry = null
        }
    }

    private fun fetchModels(token: String): List<GitHubCopilotRemoteModel> {
        val request =
            HttpRequest.newBuilder(URI.create(UrlResolver.resolveTargetUrl("/models", upstreamBaseUri.toString())))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .header("User-Agent", GitHubCopilotProxyIds.USER_AGENT)
                .header("Copilot-Integration-Id", GitHubCopilotProxyIds.COPILOT_INTEGRATION_ID)
                .header("Editor-Version", GitHubCopilotProxyIds.EDITOR_VERSION)
                .header("Editor-Plugin-Version", GitHubCopilotProxyIds.EDITOR_PLUGIN_VERSION)
                .header("X-GitHub-Api-Version", GitHubCopilotProxyIds.API_VERSION)
                .GET()
                .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) return emptyList()
        val root = JsonHelper.parseToJsonElementOrNull(response.body()) ?: return emptyList()
        val data = when (root) {
            is JsonObject -> root["data"] as? JsonArray ?: root["models"] as? JsonArray
            is JsonArray -> root
            else -> null
        } ?: return emptyList()
        return data.mapNotNull { parseRemoteModel(it) }.filter { it.supportedRoutes.isNotEmpty() }.distinctBy { it.id }
    }

    private fun parseRemoteModel(element: JsonElement): GitHubCopilotRemoteModel? {
        val item = element as? JsonObject ?: return null
        if (boolField(item, "model_picker_enabled") == false) return null
        if (stringField(item.jsonObject("policy"), "state") == "disabled") return null
        val capabilities = item.jsonObject("capabilities")
        val limits = capabilities?.jsonObject("limits")
        val supports = capabilities?.jsonObject("supports")
        val toolCalls = boolField(supports, "tool_calls") ?: true
        val id = remoteModelId(stringField(item, "id") ?: return null) ?: return null
        return GitHubCopilotRemoteModel(
            id = id,
            supportedRoutes = supportedRoutes(id, modelType(item), item["supported_endpoints"] as? JsonArray),
            supportsFunctionCalling = toolCalls,
            supportsVision = supportsVision(capabilities, supports),
            supportsPdf = supportsPdf(capabilities),
            pdfCapabilityKnown = pdfMediaTypesKnown(capabilities),
            maxInputTokens = intField(limits, "max_context_window_tokens") ?: intField(limits, "max_prompt_tokens"),
            maxOutputTokens = intField(limits, "max_output_tokens"),
            isDefault = boolField(item, "is_default") ?: boolField(item, "default") ?: false,
        )
    }

    private fun supportedRoutes(
        modelId: String,
        modelType: String?,
        endpoints: JsonArray?
    ): Set<SubscriptionProxyRoute> {
        val values = endpoints?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty()
        if (values.isEmpty()) {
            return if (modelType == "embeddings") {
                emptySet()
            } else {
                buildSet {
                    if (shouldUseResponsesApi(modelId)) add(SubscriptionProxyRoute.RESPONSES)
                    add(SubscriptionProxyRoute.CHAT_COMPLETIONS)
                }
            }
        }
        return buildSet {
            val hasChat = values.any { it.endsWith("/chat/completions") || it == "/chat/completions" }
            val hasResponses = values.any { it.endsWith("/responses") || it == "/responses" }
            val hasMessages = values.any { it == "/v1/messages" || it == "/messages" }
            if (hasMessages) {
                add(SubscriptionProxyRoute.ANTHROPIC_MESSAGES)
                return@buildSet
            }
            if (hasChat) {
                add(SubscriptionProxyRoute.CHAT_COMPLETIONS)
            }
            if (hasResponses) {
                add(SubscriptionProxyRoute.RESPONSES)
            }
            if (isEmpty()) add(SubscriptionProxyRoute.CHAT_COMPLETIONS)
        }
    }

    private data class ModelCache(
        val models: List<GitHubCopilotRemoteModel>,
        val fetchedAt: Instant,
    )

    private data class MissingModelRetry(
        val missingIds: Set<String>,
        val sequence: Long,
    )
}
