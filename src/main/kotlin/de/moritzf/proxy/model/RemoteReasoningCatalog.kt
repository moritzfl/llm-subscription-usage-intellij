package de.moritzf.proxy.model

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * A public model list (no credentials) that maps model names to reasoning levels. It loads in the
 * background, so a lookup never waits on the network and returns null until the list is in.
 */
class RemoteReasoningCatalog(
    private val fetch: () -> String?,
    private val parse: (String) -> Map<String, List<String>>,
    private val runAsync: (Runnable) -> Unit = { task ->
        Thread(task, "reasoning-catalog").apply { isDaemon = true }.start()
    },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var entries: Map<String, List<String>> = emptyMap()
    @Volatile private var nextRefreshAt = 0L
    private val loading = AtomicBoolean(false)

    fun levels(modelId: String?): List<String>? {
        refreshIfDue()
        val current = entries
        return ReasoningCatalogs.keys(modelId ?: return null).firstNotNullOfOrNull { current[it] }
    }

    private fun refreshIfDue() {
        if (clock() < nextRefreshAt || !loading.compareAndSet(false, true)) return
        runAsync {
            try {
                val parsed = runCatching { fetch()?.let(parse) }.getOrNull().orEmpty()
                // A failed refresh keeps the last good list.
                if (parsed.isNotEmpty()) entries = parsed
                nextRefreshAt = clock() + if (parsed.isNotEmpty()) REFRESH_MILLIS else RETRY_MILLIS
            } finally {
                loading.set(false)
            }
        }
    }

    private companion object {
        const val REFRESH_MILLIS = 24 * 60 * 60 * 1000L
        const val RETRY_MILLIS = 10 * 60 * 1000L
    }
}

/**
 * Best-effort reasoning levels by model name, only for Azure deployments and OpenAI-compatible
 * endpoints, whose models cannot be known in advance. OpenRouter's `/api/v1/models` answers first;
 * models.dev (the catalog OpenCode bundles) covers models OpenRouter does not know and keeps
 * working when OpenRouter is unreachable.
 */
object ReasoningCatalogs {
    const val OPENROUTER_URL = "https://openrouter.ai/api/v1/models"
    const val MODELS_DEV_URL = "https://models.dev/api.json"

    private val JSON = Json { ignoreUnknownKeys = true }
    private val disabled = System.getProperty("llm.subscription.reasoningCatalogs") == "off"

    /** models.dev providers that are the model's own vendor; their entry wins over resellers. */
    private val VENDORS =
        setOf(
            "openai",
            "anthropic",
            "google",
            "xai",
            "deepseek",
            "moonshotai",
            "zhipuai",
            "zai",
            "mistral",
            "minimax",
            "alibaba",
            "meta",
        )

    val openRouter: RemoteReasoningCatalog by lazy {
        RemoteReasoningCatalog(fetch = { download(OPENROUTER_URL) }, parse = ::parseOpenRouter)
    }

    val modelsDev: RemoteReasoningCatalog by lazy {
        RemoteReasoningCatalog(fetch = { download(MODELS_DEV_URL) }, parse = ::parseModelsDev)
    }

    fun levels(modelId: String?): List<String>? =
        openRouter.levels(modelId) ?: modelsDev.levels(modelId)

    private fun download(url: String): String? {
        if (disabled) return null
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        val request =
            HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET()
                .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return response.body().takeIf { response.statusCode() in 200..299 }
    }

    /** Model name -> levels; plain entries win over `:variant` ones such as `:free`. */
    internal fun parseOpenRouter(body: String): Map<String, List<String>> {
        val data =
            (JSON.parseToJsonElement(body) as? JsonObject)?.get("data") as? JsonArray
                ?: return emptyMap()
        val result = LinkedHashMap<String, List<String>>()
        data
            .filterIsInstance<JsonObject>()
            .mapNotNull { model -> text(model, "id")?.let { it to model } }
            .sortedBy { (id, _) -> ':' in id }
            .forEach { (id, model) ->
                val levels = ReasoningCapabilities.fromOpenRouter(model) ?: return@forEach
                keys(id.substringBefore(':')).forEach { result.putIfAbsent(it, levels) }
            }
        return result
    }

    /**
     * models.dev lists one model under many providers. The vendor's own effort levels win;
     * otherwise the set most resellers agree on. A model whose vendor marks it non-reasoning takes
     * none.
     */
    internal fun parseModelsDev(body: String): Map<String, List<String>> {
        val providers = JSON.parseToJsonElement(body) as? JsonObject ?: return emptyMap()
        val vendorLevels = HashMap<String, List<String>>()
        val resellerLevels = HashMap<String, MutableList<List<String>>>()
        providers.forEach { (providerId, provider) ->
            val models = (provider as? JsonObject)?.get("models") as? JsonObject ?: return@forEach
            val vendor = providerId in VENDORS
            models.forEach { (modelId, element) ->
                val model = element as? JsonObject ?: return@forEach
                val levels = modelsDevLevels(model, vendor) ?: return@forEach
                val names = listOfNotNull(modelId, text(model, "canonical_model_id"))
                names.flatMap(::keys).distinct().forEach { key ->
                    if (vendor) vendorLevels.putIfAbsent(key, levels)
                    else resellerLevels.getOrPut(key) { mutableListOf() } += levels
                }
            }
        }
        val result = LinkedHashMap<String, List<String>>(vendorLevels)
        resellerLevels.forEach { (key, candidates) ->
            if (key in result) return@forEach
            val chosen =
                candidates.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                    ?: return@forEach
            result[key] = chosen
        }
        return result
    }

    /**
     * Effort values; `reasoning: false` counts only from the vendor, as resellers often omit it.
     */
    private fun modelsDevLevels(model: JsonObject, vendor: Boolean): List<String>? {
        val options = model["reasoning_options"] as? JsonArray
        val effort =
            options
                ?.filterIsInstance<JsonObject>()
                ?.firstOrNull { text(it, "type") == "effort" }
                ?.get("values") as? JsonArray
        if (effort != null && effort.isNotEmpty()) {
            val values = effort.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase() }
            return ReasoningEffort.LEVELS.filter { it in values }.takeIf { it.isNotEmpty() }
        }
        val reasoning = (model["reasoning"] as? JsonPrimitive)?.booleanOrNull
        return if (vendor && reasoning == false) emptyList() else null
    }

    /** The normalized name plus its dotted/dashed version spelling (`4-6` vs `4.6`). */
    internal fun keys(modelId: String): List<String> {
        val bare = KnownReasoningLevels.normalize(modelId)
        if (bare.isEmpty()) return emptyList()
        return listOf(
                bare,
                bare.replace(Regex("(\\d)-(\\d)"), "$1.$2"),
                bare.replace(Regex("(\\d)\\.(\\d)"), "$1-$2"),
            )
            .distinct()
    }

    private fun text(item: JsonObject, key: String): String? =
        (item[key] as? JsonPrimitive)?.contentOrNull
}
