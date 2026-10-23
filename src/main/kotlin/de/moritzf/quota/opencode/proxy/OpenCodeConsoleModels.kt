package de.moritzf.quota.opencode.proxy

import de.moritzf.proxy.subscription.SubscriptionProxyModel
import de.moritzf.proxy.subscription.SubscriptionProxyRoute
import de.moritzf.quota.shared.JsonSupport
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Only the Console fields needed to route models; credentials come from the active OAuth login. */
@Serializable internal data class OpenCodeConsoleConfig(val providers: Map<String, ConsoleProvider>)

@Serializable
internal data class ConsoleProvider(
    @SerialName("package") val packageName: String? = null,
    val settings: ConsoleSettings? = null,
    val headers: Map<String, String> = emptyMap(),
    val body: JsonObject = JsonObject(emptyMap()),
    val models: Map<String, ConsoleModel> = emptyMap(),
)

@Serializable internal data class ConsoleSettings(val baseURL: String? = null)

@Serializable
internal data class ConsoleModel(
    val modelID: String? = null,
    @SerialName("package") val packageName: String? = null,
    val settings: ConsoleSettings? = null,
    val headers: Map<String, String> = emptyMap(),
    val body: JsonObject = JsonObject(emptyMap()),
    val disabled: Boolean = false,
    val capabilities: ConsoleCapabilities? = null,
    val limit: ConsoleLimit? = null,
)

@Serializable
internal data class ConsoleCapabilities(
    val tools: Boolean = true,
    val input: List<String> = emptyList(),
)

@Serializable
internal data class ConsoleLimit(
    val context: Int? = null,
    val input: Int? = null,
    val output: Int? = null,
)

internal enum class OpenCodePool {
    GO,
    ZEN,
}

internal data class OpenCodePools(
    val go: Set<String> = emptySet(),
    val zen: Set<String> = emptySet(),
)

internal data class OpenCodeConsoleModel(
    val model: SubscriptionProxyModel,
    val nativeRoute: SubscriptionProxyRoute,
    val baseUri: URI,
    val headers: Map<String, String>,
    val body: JsonObject,
    val supportsPdf: Boolean = false,
    val pdfCapabilityKnown: Boolean = false,
    val google: Boolean = false,
) {
    val targetUri: URI
        get() = URI.create(baseUri.toString().trimEnd('/') + nativeRoute.normalizedPath)

    companion object {
        fun documentModelIds(models: List<OpenCodeConsoleModel>): List<String> =
            de.moritzf.quota.shared.DocumentModelChoices.pdfOrAll(
                // Native document/vision paths post OpenAI/Anthropic payloads directly; Gemini is
                // proxy-only.
                models.filterNot { it.google }.map { it.model.upstreamId },
                models.filter { !it.google && it.supportsPdf }.map { it.model.upstreamId }.toSet(),
                models
                    .filter { !it.google && it.pdfCapabilityKnown }
                    .map { it.model.upstreamId }
                    .toSet(),
            )

        /** Only models the console lists with image input; unknown stays out, vision is opt-in. */
        fun visionModelIds(models: List<OpenCodeConsoleModel>): List<String> =
            models
                .filterNot { it.google }
                .filter { it.model.supportsVision == true }
                .map { it.model.upstreamId }

        const val GO_MODELS_URL = "https://opencode.ai/inference/go/v1/models"
        const val ZEN_MODELS_URL = "https://opencode.ai/inference/v1/models"

        /** Keep the last usable catalogs while a gateway request fails or times out. */
        private val lastPools = AtomicReference<OpenCodePools?>(null)

        private val GOOGLE_PACKAGES =
            setOf(
                "aisdk:@ai-sdk/google",
                "@opencode/ai/providers/google",
            )

        fun parse(
            body: String,
            pools: OpenCodePools = OpenCodePools(),
        ): List<OpenCodeConsoleModel> {
            val providers = JsonSupport.json.decodeFromString<OpenCodeConsoleConfig>(body).providers
            // Console Zen is provider "opencode" (/inference/…). Go is "opencode-go"
            // (/inference/go/…).
            // Do not invent the other pool's URL when that provider is already in the config.
            val configured =
                providers.mapNotNull { (id, provider) -> poolForProvider(id, provider) }.toSet()
            val disabled = disabledModels(providers)
            val parsed = providers.flatMap { (providerId, provider) ->
                provider.models.mapNotNull { (id, model) ->
                    runCatching { toModel(providerId, provider, id, model) }.getOrNull()
                }
            }
            // The Console config is an overlay: it carries only overrides and disabled
            // entries. The gateway /models lists are the actual pool catalogs.
            val present = parsed.mapTo(mutableSetOf()) { it.model.localId }
            val presentUpstream =
                parsed
                    .mapNotNull { model ->
                        poolOfLocalId(model.model.localId)?.let { it to model.model.upstreamId }
                    }
                    .groupBy({ it.first }, { it.second })
                    .mapValues { (_, ids) -> ids.toMutableSet() }
                    .toMutableMap()
            val synthesized = configured.flatMap { pool ->
                val provider =
                    providers.entries
                        .firstOrNull { (id, value) -> poolForProvider(id, value) == pool }
                        ?.value
                val upstreamIds = presentUpstream.getOrPut(pool) { mutableSetOf() }
                val disabledIds = disabled[pool].orEmpty()
                poolIds(pools, pool).mapNotNull { id ->
                    val localId = localId(pool, id)
                    val model = provider?.models?.get(id)
                    val upstreamId = model?.modelID ?: id
                    if (
                        localId in present ||
                            id in disabledIds ||
                            upstreamId in disabledIds ||
                            upstreamId in upstreamIds ||
                            model?.disabled == true
                    )
                        return@mapNotNull null
                    runCatching { synthesized(pool, id, model, provider) }
                        .getOrNull()
                        ?.also {
                            present += localId
                            upstreamIds += it.model.upstreamId
                        }
                }
            }
            return withPoolTwins(parsed + synthesized, pools, configured)
        }

        fun fetchPools(httpClient: HttpClient = HttpClient.newHttpClient()): OpenCodePools {
            val go = fetchIds(httpClient, GO_MODELS_URL)
            val zen = fetchIds(httpClient, ZEN_MODELS_URL)
            val previous = lastPools.get()
            val merged =
                OpenCodePools(
                    go = go.ifEmpty { previous?.go.orEmpty() },
                    zen = zen.ifEmpty { previous?.zen.orEmpty() },
                )
            if (merged.go.isNotEmpty() || merged.zen.isNotEmpty()) lastPools.set(merged)
            return merged
        }

        internal fun poolOf(uri: URI): OpenCodePool? {
            val path = uri.path.trimEnd('/')
            return when {
                isGoPath(path) -> OpenCodePool.GO
                isZenPath(path) -> OpenCodePool.ZEN
                else -> null
            }
        }

        private fun providerPool(providerId: String): OpenCodePool? =
            when (providerId) {
                "opencode-go" -> OpenCodePool.GO
                "opencode" -> OpenCodePool.ZEN
                else -> null
            }

        /** Disabled entries may use an alias key; collect both the catalog id and upstream id. */
        private fun disabledModels(
            providers: Map<String, ConsoleProvider>
        ): Map<OpenCodePool, Set<String>> {
            val disabled = mutableMapOf<OpenCodePool, MutableSet<String>>()
            providers.forEach { (providerId, provider) ->
                val pool = poolForProvider(providerId, provider) ?: return@forEach
                provider.models.forEach { (id, model) ->
                    if (!model.disabled) return@forEach
                    val ids = disabled.getOrPut(pool) { mutableSetOf() }
                    ids += id
                    model.modelID?.let(ids::add)
                }
            }
            return disabled
        }

        private fun poolForProvider(
            providerId: String,
            provider: ConsoleProvider,
        ): OpenCodePool? =
            providerPool(providerId)
                ?: provider.settings?.baseURL?.let {
                    runCatching { poolOf(URI.create(it)) }.getOrNull()
                }

        private fun poolFor(providerId: String, uri: URI): OpenCodePool? =
            poolOf(uri) ?: providerPool(providerId)

        private fun poolIds(pools: OpenCodePools, pool: OpenCodePool): Set<String> =
            when (pool) {
                OpenCodePool.GO -> pools.go
                OpenCodePool.ZEN -> pools.zen
            }

        /** Protocol the gateway speaks for a model, independent of our local proxy route. */
        private data class WireRoute(
            val route: SubscriptionProxyRoute,
            val google: Boolean = false,
        )

        /** The Console still serves `/zen/…` base URLs next to `/inference/…`; both name a pool. */
        private val POOL_ROOTS = listOf("/inference", "/zen")

        private fun isGoPath(path: String): Boolean = POOL_ROOTS.any {
            path.contains("$it/go/") || path.endsWith("$it/go")
        }

        private fun isZenPath(path: String): Boolean =
            !isGoPath(path) && POOL_ROOTS.any { path.contains("$it/") || path.endsWith(it) }

        internal fun localId(pool: OpenCodePool?, modelId: String): String =
            when (pool) {
                OpenCodePool.GO -> "oc-go-$modelId"
                OpenCodePool.ZEN -> "oc-zen-$modelId"
                null -> "oc-$modelId"
            }

        private fun poolOfLocalId(localId: String): OpenCodePool? =
            when {
                localId.startsWith(OpenCodeZenSubscriptionProxyProvider.GO_PREFIX) ->
                    OpenCodePool.GO
                localId.startsWith(OpenCodeZenSubscriptionProxyProvider.ZEN_PREFIX) ->
                    OpenCodePool.ZEN
                else -> null
            }

        internal fun rewritePoolBase(uri: URI, target: OpenCodePool): URI? {
            val raw = uri.toString().trimEnd('/')
            val path = uri.path.trimEnd('/')
            val root =
                POOL_ROOTS.firstOrNull { raw.contains("$it/") || raw.endsWith(it) } ?: return null
            val rewritten =
                when (target) {
                    OpenCodePool.GO ->
                        if (isGoPath(path) || !raw.contains("$root/")) return null
                        else raw.replaceFirst("$root/", "$root/go/")
                    OpenCodePool.ZEN ->
                        when {
                            raw.contains("$root/go/") -> raw.replaceFirst("$root/go/", "$root/")
                            raw.endsWith("$root/go") -> raw.removeSuffix("/go")
                            else -> return null
                        }
                }
            return runCatching { URI.create(rewritten) }.getOrNull()
        }

        private fun toModel(
            providerId: String,
            provider: ConsoleProvider,
            id: String,
            model: ConsoleModel,
        ): OpenCodeConsoleModel? {
            if (model.disabled) return null
            val packageName = model.packageName ?: provider.packageName
            val upstreamId = model.modelID ?: id
            val explicitBase = model.settings?.baseURL ?: provider.settings?.baseURL
            val pool =
                explicitBase?.let {
                    runCatching { poolFor(providerId, URI.create(it)) }.getOrNull()
                } ?: providerPool(providerId) ?: return null
            val wireRoute = wireRoute(packageName, upstreamId, pool) ?: return null
            val uri = explicitBase?.let(::inferenceUri) ?: defaultBase(pool, wireRoute)
            return entry(id, upstreamId, model, provider, wireRoute, uri, pool)
        }

        /**
         * The Console config is sparse: it lists overrides and disabled models, while the gateways
         * serve the full pool catalog. Build the missing entries from the gateway list.
         */
        private fun synthesized(
            pool: OpenCodePool,
            id: String,
            model: ConsoleModel?,
            provider: ConsoleProvider?,
        ): OpenCodeConsoleModel? {
            val config = model ?: ConsoleModel()
            val packageName = config.packageName
            val upstreamId = config.modelID ?: id
            val wireRoute = wireRoute(packageName, upstreamId, pool) ?: return null
            return entry(
                id,
                upstreamId,
                config,
                provider ?: ConsoleProvider(),
                wireRoute,
                defaultBase(pool, wireRoute),
                pool,
            )
        }

        private fun inferenceUri(value: String): URI {
            val uri = URI.create(value)
            require(
                uri.scheme in setOf("https", "http") &&
                    uri.host != null &&
                    uri.userInfo == null &&
                    uri.query == null &&
                    uri.fragment == null
            ) {
                "Invalid OpenCode inference URL"
            }
            return uri
        }

        /**
         * Explicit Console packages win. The public model lists carry no package, so infer the
         * gateway protocol from the model id, matching the Console's own routing.
         */
        private fun wireRoute(
            packageName: String?,
            modelId: String,
            pool: OpenCodePool,
        ): WireRoute? {
            when (packageName) {
                in GOOGLE_PACKAGES ->
                    return WireRoute(SubscriptionProxyRoute.CHAT_COMPLETIONS, true)
                "aisdk:@ai-sdk/openai-compatible",
                "@opencode/ai/providers/openai-compatible" ->
                    return WireRoute(SubscriptionProxyRoute.CHAT_COMPLETIONS)
                "aisdk:@ai-sdk/openai",
                "@opencode/ai/providers/openai",
                "@opencode/ai/providers/openai-compatible-responses" ->
                    return WireRoute(SubscriptionProxyRoute.RESPONSES)
                "aisdk:@ai-sdk/anthropic",
                "@opencode/ai/providers/anthropic",
                "@opencode/ai/providers/anthropic-compatible" ->
                    return WireRoute(SubscriptionProxyRoute.ANTHROPIC_MESSAGES)
                null -> Unit
                else -> return null
            }
            return when {
                modelId.startsWith("gpt-") -> WireRoute(SubscriptionProxyRoute.RESPONSES)
                modelId.startsWith("claude-") ->
                    WireRoute(SubscriptionProxyRoute.ANTHROPIC_MESSAGES)
                modelId.startsWith("grok-") -> WireRoute(SubscriptionProxyRoute.RESPONSES)
                modelId.startsWith("muse-spark-") || modelId.startsWith("muse-wonder-") ->
                    WireRoute(SubscriptionProxyRoute.RESPONSES)
                // Console serves qwen3.8-max as Chat Completions, Go as Anthropic Messages.
                modelId == "qwen3.8-max" ->
                    WireRoute(
                        if (pool == OpenCodePool.GO) SubscriptionProxyRoute.ANTHROPIC_MESSAGES
                        else SubscriptionProxyRoute.CHAT_COMPLETIONS
                    )
                modelId.startsWith("qwen") -> WireRoute(SubscriptionProxyRoute.ANTHROPIC_MESSAGES)
                // MiniMax uses Anthropic Messages on Go and Chat Completions on Console.
                pool == OpenCodePool.GO && modelId.startsWith("minimax-") ->
                    WireRoute(SubscriptionProxyRoute.ANTHROPIC_MESSAGES)
                modelId.startsWith("gemini-") ->
                    WireRoute(SubscriptionProxyRoute.CHAT_COMPLETIONS, true)
                else -> WireRoute(SubscriptionProxyRoute.CHAT_COMPLETIONS)
            }
        }

        private fun defaultBase(pool: OpenCodePool, wireRoute: WireRoute): URI {
            val root =
                if (pool == OpenCodePool.GO) "https://opencode.ai/inference/go"
                else "https://opencode.ai/inference"
            val path =
                when {
                    wireRoute.google -> "google/v1beta"
                    wireRoute.route == SubscriptionProxyRoute.ANTHROPIC_MESSAGES -> "anthropic/v1"
                    else -> "openai/v1"
                }
            return URI.create("$root/$path")
        }

        private fun entry(
            id: String,
            upstreamId: String,
            model: ConsoleModel,
            provider: ConsoleProvider,
            wireRoute: WireRoute,
            uri: URI,
            pool: OpenCodePool,
        ): OpenCodeConsoleModel {
            val nativeRoute = wireRoute.route
            val google = wireRoute.google
            return OpenCodeConsoleModel(
                model =
                    SubscriptionProxyModel(
                        localId = localId(pool, id),
                        upstreamId = upstreamId,
                        providerId = OpenCodeZenSubscriptionProxyProvider.ID,
                        providerName = poolLabel(pool),
                        litellmProvider = "opencode",
                        supportedRoutes =
                            buildSet {
                                add(SubscriptionProxyRoute.CHAT_COMPLETIONS)
                                if (!google) add(nativeRoute)
                            },
                        supportsFunctionCalling = model.capabilities?.tools ?: true,
                        supportsToolChoice = model.capabilities?.tools ?: true,
                        supportsVision = model.capabilities?.input?.contains("image") == true,
                        maxInputTokens = model.limit?.input ?: model.limit?.context,
                        maxOutputTokens = model.limit?.output,
                        reasoningEfforts =
                            if (!google && nativeRoute == SubscriptionProxyRoute.CHAT_COMPLETIONS) {
                                chatReasoningEfforts(upstreamId)
                            } else {
                                // Messages and Responses models get their effort fitted on their
                                // own protocol (AnthropicReasoning, ModelAliasResolver).
                                null
                            },
                    ),
                nativeRoute = nativeRoute,
                baseUri = uri,
                headers = provider.headers + model.headers,
                body = JsonObject(provider.body + model.body),
                supportsPdf = model.capabilities?.input?.contains("pdf") == true,
                pdfCapabilityKnown = model.capabilities?.input?.isNotEmpty() == true,
                google = google,
            )
        }

        private fun withPoolTwins(
            models: List<OpenCodeConsoleModel>,
            pools: OpenCodePools,
            configured: Set<OpenCodePool>,
        ): List<OpenCodeConsoleModel> {
            val present = models.map { it.model.localId }.toMutableSet()
            val twins = models.mapNotNull { model ->
                val pool = poolOf(model.baseUri) ?: return@mapNotNull null
                val other = if (pool == OpenCodePool.ZEN) OpenCodePool.GO else OpenCodePool.ZEN
                if (other in configured) return@mapNotNull null
                val ids = if (other == OpenCodePool.GO) pools.go else pools.zen
                val catalogId = catalogId(model)
                if (catalogId !in ids && model.model.upstreamId !in ids) return@mapNotNull null
                val rewritten = rewritePoolBase(model.baseUri, other) ?: return@mapNotNull null
                val id = localId(other, catalogId)
                if (!present.add(id)) return@mapNotNull null
                model.copy(
                    model = model.model.copy(localId = id, providerName = poolLabel(other)),
                    baseUri = rewritten,
                )
            }
            return models + twins
        }

        private fun catalogId(model: OpenCodeConsoleModel): String {
            val local = model.model.localId
            return when {
                local.startsWith("oc-go-") -> local.removePrefix("oc-go-")
                local.startsWith("oc-zen-") -> local.removePrefix("oc-zen-")
                local.startsWith("oc-") -> local.removePrefix("oc-")
                else -> model.model.upstreamId
            }
        }

        /**
         * Probed on OpenCode Go (2026-10-23): GLM-5.3 is thinking-only and rejects
         * `reasoning_effort: none` with HTTP 400; DeepSeek and Kimi accept every value, so they
         * stay unknown and forward as sent.
         */
        private fun chatReasoningEfforts(upstreamId: String): List<String>? =
            if (upstreamId.lowercase().startsWith("glm-")) {
                listOf("low", "medium", "high", "xhigh", "max")
            } else {
                null
            }

        private fun poolLabel(pool: OpenCodePool?): String =
            when (pool) {
                OpenCodePool.GO -> "OpenCode Go"
                OpenCodePool.ZEN -> "OpenCode Zen"
                null -> "OpenCode"
            }

        private fun fetchIds(httpClient: HttpClient, url: String): Set<String> {
            return runCatching {
                    val request =
                        HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(8))
                            .header("Accept", "application/json")
                            .GET()
                            .build()
                    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                    if (response.statusCode() !in 200..299) return emptySet()
                    val data =
                        (JsonSupport.json.parseToJsonElement(response.body()) as? JsonObject)?.get(
                            "data"
                        ) as? JsonArray ?: return emptySet()
                    data
                        .mapNotNull {
                            (it as? JsonObject)?.get("id")?.let { id ->
                                (id as? JsonPrimitive)?.contentOrNull
                            }
                        }
                        .toSet()
                }
                .getOrDefault(emptySet())
        }
    }
}
