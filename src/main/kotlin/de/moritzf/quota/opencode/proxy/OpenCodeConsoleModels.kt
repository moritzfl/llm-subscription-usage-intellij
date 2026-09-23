package de.moritzf.quota.opencode.proxy

import de.moritzf.proxy.subscription.SubscriptionProxyModel
import de.moritzf.proxy.subscription.SubscriptionProxyRoute
import de.moritzf.quota.shared.JsonSupport
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Only the Console fields needed to route models; credentials come from the active OAuth login. */
@Serializable
internal data class OpenCodeConsoleConfig(val providers: Map<String, ConsoleProvider>)

@Serializable
internal data class ConsoleProvider(
    @SerialName("package") val packageName: String? = null,
    val settings: ConsoleSettings? = null,
    val headers: Map<String, String> = emptyMap(),
    val body: JsonObject = JsonObject(emptyMap()),
    val models: Map<String, ConsoleModel> = emptyMap(),
)

@Serializable
internal data class ConsoleSettings(val baseURL: String? = null)

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
internal data class ConsoleCapabilities(val tools: Boolean = true, val input: List<String> = emptyList())

@Serializable
internal data class ConsoleLimit(val context: Int? = null, val input: Int? = null, val output: Int? = null)

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
) {
    val targetUri: URI get() = URI.create(baseUri.toString().trimEnd('/') + nativeRoute.normalizedPath)

    companion object {
        const val GO_MODELS_URL = "https://opencode.ai/zen/go/v1/models"
        const val ZEN_MODELS_URL = "https://opencode.ai/zen/v1/models"

        fun parse(body: String, pools: OpenCodePools = OpenCodePools()): List<OpenCodeConsoleModel> {
            val providers = JsonSupport.json.decodeFromString<OpenCodeConsoleConfig>(body).providers
            // Console Zen is provider "opencode" (/inference/…). Go is "opencode-go" (/inference/go/…).
            // Do not invent the other pool's URL when that provider is already in the config.
            val configured = providers.keys.mapNotNull(::providerPool).toSet()
            val parsed = providers.flatMap { (providerId, provider) ->
                provider.models.mapNotNull { (id, model) -> toModel(providerId, provider, id, model) }
            }
            return withPoolTwins(parsed, pools, configured)
        }

        fun fetchPools(httpClient: HttpClient = HttpClient.newHttpClient()): OpenCodePools {
            return OpenCodePools(fetchIds(httpClient, GO_MODELS_URL), fetchIds(httpClient, ZEN_MODELS_URL))
        }

        internal fun poolOf(uri: URI): OpenCodePool? {
            val path = uri.path.trimEnd('/')
            return when {
                isGoPath(path) -> OpenCodePool.GO
                isZenPath(path) -> OpenCodePool.ZEN
                else -> null
            }
        }

        private fun providerPool(providerId: String): OpenCodePool? = when (providerId) {
            "opencode-go" -> OpenCodePool.GO
            "opencode" -> OpenCodePool.ZEN
            else -> null
        }

        private fun poolFor(providerId: String, uri: URI): OpenCodePool? = poolOf(uri) ?: providerPool(providerId)

        private fun isGoPath(path: String): Boolean {
            return path.contains("/zen/go/") || path.endsWith("/zen/go") ||
                path.contains("/inference/go/") || path.endsWith("/inference/go")
        }

        private fun isZenPath(path: String): Boolean {
            if (isGoPath(path)) return false
            return path.contains("/zen/") || path.endsWith("/zen") ||
                path.contains("/inference/") || path.endsWith("/inference")
        }

        internal fun localId(pool: OpenCodePool?, modelId: String): String = when (pool) {
            OpenCodePool.GO -> "oc-go-$modelId"
            OpenCodePool.ZEN -> "oc-zen-$modelId"
            null -> "oc-$modelId"
        }

        internal fun rewritePoolBase(uri: URI, target: OpenCodePool): URI? {
            val raw = uri.toString().trimEnd('/')
            val path = uri.path.trimEnd('/')
            val rewritten = when (target) {
                OpenCodePool.GO -> if (isGoPath(path)) {
                    return null
                } else if (raw.contains("/zen/")) {
                    raw.replace("/zen/", "/zen/go/")
                } else if (raw.contains("/inference/")) {
                    raw.replace("/inference/", "/inference/go/")
                } else {
                    return null
                }
                OpenCodePool.ZEN -> if (raw.contains("/zen/go/")) {
                    raw.replace("/zen/go/", "/zen/")
                } else if (raw.endsWith("/zen/go")) {
                    raw.removeSuffix("/go")
                } else if (raw.contains("/inference/go/")) {
                    raw.replace("/inference/go/", "/inference/")
                } else if (raw.endsWith("/inference/go")) {
                    raw.removeSuffix("/go")
                } else {
                    return null
                }
            }
            return runCatching { URI.create(rewritten) }.getOrNull()
        }

        private fun toModel(providerId: String, provider: ConsoleProvider, id: String, model: ConsoleModel): OpenCodeConsoleModel? {
            if (model.disabled) return null
            val nativeRoute = when (model.packageName ?: provider.packageName) {
                "aisdk:@ai-sdk/openai-compatible", "@opencode/ai/providers/openai-compatible" -> SubscriptionProxyRoute.CHAT_COMPLETIONS
                "aisdk:@ai-sdk/openai", "@opencode/ai/providers/openai", "@opencode/ai/providers/openai-compatible-responses" -> SubscriptionProxyRoute.RESPONSES
                "aisdk:@ai-sdk/anthropic", "@opencode/ai/providers/anthropic", "@opencode/ai/providers/anthropic-compatible" -> SubscriptionProxyRoute.ANTHROPIC_MESSAGES
                else -> return null
            }
            val base = model.settings?.baseURL ?: provider.settings?.baseURL ?: return null
            val uri = URI.create(base)
            require(uri.scheme in setOf("https", "http") && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null) {
                "Invalid OpenCode inference URL"
            }
            val pool = poolFor(providerId, uri) ?: return null
            return entry(id, model.modelID ?: id, model, provider, nativeRoute, uri, pool)
        }

        private fun entry(
            id: String,
            upstreamId: String,
            model: ConsoleModel,
            provider: ConsoleProvider,
            nativeRoute: SubscriptionProxyRoute,
            uri: URI,
            pool: OpenCodePool,
        ): OpenCodeConsoleModel {
            return OpenCodeConsoleModel(
                model = SubscriptionProxyModel(
                    localId = localId(pool, id),
                    upstreamId = upstreamId,
                    providerId = OpenCodeZenSubscriptionProxyProvider.ID,
                    providerName = poolLabel(pool),
                    litellmProvider = "opencode",
                    supportedRoutes = setOf(SubscriptionProxyRoute.CHAT_COMPLETIONS, nativeRoute),
                    supportsFunctionCalling = model.capabilities?.tools ?: true,
                    supportsToolChoice = model.capabilities?.tools ?: true,
                    supportsVision = model.capabilities?.input?.contains("image") == true,
                    maxInputTokens = model.limit?.input ?: model.limit?.context,
                    maxOutputTokens = model.limit?.output,
                ),
                nativeRoute = nativeRoute,
                baseUri = uri,
                headers = provider.headers + model.headers,
                body = JsonObject(provider.body + model.body),
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

        private fun poolLabel(pool: OpenCodePool?): String = when (pool) {
            OpenCodePool.GO -> "OpenCode Go"
            OpenCodePool.ZEN -> "OpenCode Zen"
            null -> "OpenCode"
        }

        private fun fetchIds(httpClient: HttpClient, url: String): Set<String> {
            return runCatching {
                val request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json")
                    .GET()
                    .build()
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) return emptySet()
                val data = (JsonSupport.json.parseToJsonElement(response.body()) as? JsonObject)?.get("data") as? JsonArray
                    ?: return emptySet()
                data.mapNotNull { (it as? JsonObject)?.get("id")?.let { id -> (id as? JsonPrimitive)?.contentOrNull } }.toSet()
            }.getOrDefault(emptySet())
        }
    }
}
