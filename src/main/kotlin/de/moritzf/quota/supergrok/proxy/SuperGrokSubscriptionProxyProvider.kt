package de.moritzf.quota.supergrok.proxy

import de.moritzf.proxy.logging.RequestLogger
import de.moritzf.proxy.server.JsonHelper
import de.moritzf.proxy.server.isTextual
import de.moritzf.proxy.server.remove
import de.moritzf.proxy.server.text
import de.moritzf.proxy.subscription.PassThroughSubscriptionProxyProvider
import de.moritzf.proxy.subscription.SubscriptionProxyModel
import de.moritzf.proxy.subscription.SubscriptionProxyProvider
import de.moritzf.proxy.subscription.SubscriptionProxyRequest
import de.moritzf.proxy.subscription.SubscriptionProxyRoute
import de.moritzf.proxy.transport.UrlResolver
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

class SuperGrokSubscriptionProxyProvider(
    private val accessTokenProvider: () -> String?,
    private val tokenRefresher: (staleAccessToken: String?) -> String? = { null },
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build(),
    private val upstreamBaseUri: URI = DEFAULT_UPSTREAM_BASE_URI,
    fullRequestLogging: Boolean = false,
    requestLogDir: String = DEFAULT_REQUEST_LOG_DIR,
) : SubscriptionProxyProvider {
    private val delegate = PassThroughSubscriptionProxyProvider(
        id = ID,
        displayName = DISPLAY_NAME,
        litellmProvider = LITELLM_PROVIDER,
        baseUri = upstreamBaseUri,
        accessTokenProvider = accessTokenProvider,
        tokenRefresher = tokenRefresher,
        modelMappingsProvider = ::modelMappings,
        defaultHeaders = mapOf(
            "Accept" to "application/json",
            "User-Agent" to "openai-usage-quota-intellij",
        ),
        requestBodyTransformer = ::requestBody,
        jsonResponseTransformer = ::jsonResponse,
        httpClient = httpClient,
        requestLogger = RequestLogger(fullRequestLogging, Path.of(requestLogDir)),
    )

    @Volatile private var modelCache: ModelCache? = null

    override val id: String = ID
    override val displayName: String = DISPLAY_NAME

    override fun isConfigured(): Boolean = delegate.isConfigured()

    override fun models() = delegate.models()

    override fun fallbackModel(localId: String, route: SubscriptionProxyRoute): SubscriptionProxyModel? {
        if (route !in SUPPORTED_ROUTES) return null
        val upstreamId = localId.trim()
            .takeIf { it.startsWith(PREFIX) && it.length > PREFIX.length }
            ?.removePrefix(PREFIX)
            ?: return null
        return SubscriptionProxyModel(
            localId = localId,
            upstreamId = upstreamId,
            providerId = id,
            providerName = displayName,
            litellmProvider = LITELLM_PROVIDER,
            supportedRoutes = setOf(route),
            supportsFunctionCalling = true,
            supportsToolChoice = true,
            supportsVision = true,
        )
    }

    override suspend fun handle(ctx: de.moritzf.proxy.server.ProxyCall, request: SubscriptionProxyRequest) {
        delegate.handle(ctx, request)
    }

    private fun modelMappings(): List<PassThroughSubscriptionProxyProvider.ModelMapping> {
        return remoteModels().map { model ->
            PassThroughSubscriptionProxyProvider.ModelMapping(
                localId = PREFIX + model.id,
                upstreamId = model.id,
                supportedRoutes = SUPPORTED_ROUTES,
                supportsPromptCaching = false,
                isDefault = model.isDefault,
            )
        }
    }

    private fun requestBody(request: SubscriptionProxyRequest, body: JsonObject): JsonObject {
        if (request.route != SubscriptionProxyRoute.CHAT_COMPLETIONS || body["stop"] == null) return body
        return body.remove("stop")
    }

    private fun jsonResponse(request: SubscriptionProxyRequest, body: String): String {
        if (request.route != SubscriptionProxyRoute.CHAT_COMPLETIONS || body.isBlank()) return body
        val stopSequences = stopSequences(request.body)
        if (stopSequences.isEmpty()) return body
        val root = JsonHelper.parseToJsonElementOrNull(body) as? JsonObject ?: return body
        val choices = root["choices"] as? JsonArray ?: return body
        var changed = false
        val updatedChoices = choices.map { choiceElement ->
            val choice = choiceElement as? JsonObject ?: return@map choiceElement
            val message = choice["message"] as? JsonObject ?: return@map choiceElement
            val content = message["content"]
            if (!content.isTextual()) return@map choiceElement
            val cut = cutAtStopSequence(content.text, stopSequences) ?: return@map choiceElement
            changed = true
            val updatedMessage = buildJsonObject {
                message.forEach { (key, value) ->
                    put(key, if (key == "content") JsonPrimitive(cut.content) else value)
                }
            }
            buildJsonObject {
                choice.forEach { (key, value) ->
                    if (key != "message" && key != "finish_reason" && key != "finish_details") put(key, value)
                }
                put("message", updatedMessage)
                put("finish_reason", "stop")
                put("finish_details", buildJsonObject {
                    put("type", "stop")
                    put("stop", cut.sequence)
                })
            }
        }
        if (!changed) return body
        return JsonHelper.encodeToString(buildJsonObject {
            root.forEach { (key, value) ->
                put(key, if (key == "choices") JsonArray(updatedChoices) else value)
            }
        })
    }

    @OptIn(ExperimentalTime::class)
    private fun remoteModels(): List<RemoteModel> {
        val now = Clock.System.now()
        val cached = modelCache
        if (cached != null && now - cached.fetchedAt < CACHE_TTL) {
            return cached.models
        }
        val token = accessTokenProvider().trimmedOrNull() ?: return emptyList()
        val models = runCatching { fetchModels(token) }.getOrDefault(emptyList())
        if (models.isNotEmpty()) {
            modelCache = ModelCache(models, now)
        }
        return models
    }

    private fun fetchModels(token: String): List<RemoteModel> {
        val request = HttpRequest.newBuilder(URI.create(UrlResolver.resolveTargetUrl("/models", upstreamBaseUri.toString())))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .header("User-Agent", "openai-usage-quota-intellij")
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) return emptyList()
        val root = JsonHelper.parseToJsonElementOrNull(response.body()) as? JsonObject ?: return emptyList()
        val data = root["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { parseRemoteModel(it) }.distinctBy { it.id }
    }

    private fun parseRemoteModel(element: JsonElement): RemoteModel? {
        val item = element as? JsonObject ?: return null
        val id = (item["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        if (!supportsTextInference(id, item)) return null
        return RemoteModel(
            id = id,
            isDefault = booleanField(item, "is_default") ?: booleanField(item, "default") ?: false,
        )
    }

    private data class RemoteModel(
        val id: String,
        val isDefault: Boolean,
    )

    private fun booleanField(item: JsonObject, name: String): Boolean? {
        return (item[name] as? JsonPrimitive)?.booleanOrNull
    }

    private fun supportsTextInference(id: String, item: JsonObject): Boolean {
        if (item["prompt_text_token_price"] != null || item["completion_text_token_price"] != null) {
            return true
        }
        if (item["image_price"] != null || id.contains("imagine", ignoreCase = true)) {
            return false
        }
        return true
    }

    private data class ModelCache(
        val models: List<RemoteModel>,
        val fetchedAt: Instant,
    )

    private data class StopCut(val content: String, val sequence: String)

    companion object {
        const val ID = "supergrok"
        const val PREFIX = "sg-"
        private const val DISPLAY_NAME = "SuperGrok"
        private const val LITELLM_PROVIDER = "xai"
        private val SUPPORTED_ROUTES = setOf(SubscriptionProxyRoute.CHAT_COMPLETIONS, SubscriptionProxyRoute.RESPONSES)
        val DEFAULT_UPSTREAM_BASE_URI: URI = URI.create("https://api.x.ai/v1")
        private val CACHE_TTL = 5.minutes
        private val DEFAULT_REQUEST_LOG_DIR = System.getProperty("java.io.tmpdir") +
            "/openai-usage-quota-intellij/subscription-proxy-supergrok-requests"

        private fun String?.trimmedOrNull(): String? = this?.trim()?.takeIf { it.isNotBlank() }

        private fun stopSequences(body: JsonObject): List<String> {
            val stop = body["stop"] ?: return emptyList()
            if (stop.isTextual() && stop.text.isNotEmpty()) return listOf(stop.text)
            if (stop !is JsonArray) return emptyList()
            return stop.mapNotNull { sequence ->
                sequence.takeIf { it.isTextual() }?.text?.takeIf { it.isNotEmpty() }
            }
        }

        private fun cutAtStopSequence(text: String, stopSequences: List<String>): StopCut? {
            var earliestStart = -1
            var firedSequence: String? = null
            for (sequence in stopSequences) {
                val start = text.indexOf(sequence)
                if (start == -1) continue
                if (earliestStart == -1 || start < earliestStart) {
                    earliestStart = start
                    firedSequence = sequence
                }
            }
            return if (firedSequence != null) StopCut(text.substring(0, earliestStart), firedSequence) else null
        }
    }
}
