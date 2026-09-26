package de.moritzf.quota.github.proxy

import de.moritzf.proxy.subscription.SubscriptionProxyRoute
import de.moritzf.proxy.transport.UrlResolver
import de.moritzf.quota.shared.DocumentModelChoices
import de.moritzf.quota.shared.JsonSupport
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.URI
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

internal object GitHubCopilotProxyIds {
    const val ID = "github"
    const val PREFIX = "gh-"
    const val OPENCODE_PROVIDER_PREFIX = "github-copilot/"
    const val DISPLAY_NAME = "GitHub Copilot"
    const val DEFAULT_RESPONSES_INSTRUCTIONS = "You are a coding assistant."
    const val LITELLM_PROVIDER = "github_copilot"
    const val USER_AGENT = "GitHubCopilotChat/0.26.7"
    const val COPILOT_INTEGRATION_ID = "vscode-chat"
    const val EDITOR_VERSION = "vscode/1.104.1"
    const val EDITOR_PLUGIN_VERSION = "copilot-chat/0.26.7"
    const val API_VERSION = "2026-06-01"
    const val DEFAULT_ANTHROPIC_MAX_TOKENS = 4096
    const val MAX_REMOTE_IMAGE_BYTES = 5 * 1024 * 1024
    val REMOTE_IMAGE_TIMEOUT: Duration = Duration.ofSeconds(15)
    val OPENAI_CHAT_ENVELOPE_FIELDS = setOf("id", "object", "created", "model", "choices")
    val UNSUPPORTED_MESSAGES_BODY_FIELDS = setOf("context_management", "output_config", "thinking")
    val DEFAULT_UPSTREAM_BASE_URI: URI = URI.create("https://api.githubcopilot.com")
    val DEFAULT_CACHE_TTL = 5.minutes
    val DEFAULT_MISSING_MODEL_RETRY_DELAYS: List<Duration> = List(10) { index ->
        Duration.ofMillis(1_000L shl index)
    }
    val DEFAULT_REQUEST_LOG_DIR: String = System.getProperty("java.io.tmpdir") +
        "/openai-usage-quota-intellij/subscription-proxy-github-requests"
    val MODEL_RETRY_SEQUENCE = AtomicLong()
}

private val GPT_MAJOR_REGEX = Regex("^gpt-(\\d+)")

internal fun String?.trimmedOrNull(): String? = this?.trim()?.takeIf { it.isNotBlank() }

internal fun intField(item: JsonObject?, name: String): Int? {
    return (item?.get(name) as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
}

internal fun boolField(item: JsonObject?, name: String): Boolean? {
    return (item?.get(name) as? JsonPrimitive)?.booleanOrNull
}

internal fun stringField(item: JsonObject?, name: String): String? {
    return (item?.get(name) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}

internal fun JsonObject.jsonObject(name: String): JsonObject? = this[name] as? JsonObject

internal fun modelType(item: JsonObject): String? {
    val capabilities = item["capabilities"] as? JsonObject ?: return null
    return (capabilities["type"] as? JsonPrimitive)?.contentOrNull
}

internal fun remoteModelId(rawId: String): String? {
    return rawId.trim()
        .removePrefix(GitHubCopilotProxyIds.OPENCODE_PROVIDER_PREFIX)
        .takeIf { it.isNotBlank() }
}

internal fun fallbackUpstreamId(localId: String): String? {
    val trimmed = localId.trim()
    val upstreamId = when {
        trimmed.startsWith(GitHubCopilotProxyIds.PREFIX) -> trimmed.removePrefix(GitHubCopilotProxyIds.PREFIX)
        trimmed.startsWith(GitHubCopilotProxyIds.OPENCODE_PROVIDER_PREFIX) ->
            trimmed.removePrefix(GitHubCopilotProxyIds.OPENCODE_PROVIDER_PREFIX)
        else -> return null
    }
    return upstreamId.takeIf { it.isNotBlank() }
}

internal fun supportsVision(capabilities: JsonObject?, supports: JsonObject?): Boolean {
    if (boolField(supports, "vision") == true) return true
    return mediaTypes(capabilities).any { it.startsWith("image/") }
}

/** True only when Copilot lists `application/pdf`. Missing media types are unknown, not a no. */
internal fun supportsPdf(capabilities: JsonObject?): Boolean =
    mediaTypes(capabilities).any { it == "application/pdf" }

internal fun pdfMediaTypesKnown(capabilities: JsonObject?): Boolean = mediaTypes(capabilities).isNotEmpty()

internal data class GitHubListedModel(
    val id: String,
    val supportsPdf: Boolean,
    val pdfCapabilityKnown: Boolean,
    val endpoints: List<String>,
)

internal fun githubDocumentModelIds(models: List<GitHubListedModel>): List<String> =
    DocumentModelChoices.pdfOrAll(
        models.map { it.id },
        models.filter { it.supportsPdf }.map { it.id }.toSet(),
        models.filter { it.pdfCapabilityKnown }.map { it.id }.toSet(),
    )

/** Copilot `/models` body. PDF models when that model says so; every other model when it does not. */
internal fun githubDocumentModelIds(body: String): List<String> = githubDocumentModelIds(parseGitHubListedModels(body))

internal fun parseGitHubListedModels(body: String): List<GitHubListedModel> {
    val root = runCatching { JsonSupport.json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
    val data = when (root) {
        is JsonObject -> root["data"] as? JsonArray ?: root["models"] as? JsonArray
        is JsonArray -> root
        else -> null
    } ?: return emptyList()
    return data.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        if (boolField(item, "model_picker_enabled") == false) return@mapNotNull null
        if (stringField(item.jsonObject("policy"), "state") == "disabled") return@mapNotNull null
        if (modelType(item) == "embeddings") return@mapNotNull null
        val id = remoteModelId(stringField(item, "id") ?: return@mapNotNull null) ?: return@mapNotNull null
        val capabilities = item["capabilities"] as? JsonObject
        val endpoints = (item["supported_endpoints"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        GitHubListedModel(id, supportsPdf(capabilities), pdfMediaTypesKnown(capabilities), endpoints)
    }.distinctBy { it.id }
}

/**
 * Live `supported_endpoints` wins. A model id is only a fallback when Copilot did not list routes,
 * so a new id the user picked still has a request path.
 */
internal fun githubDocumentRoute(modelId: String, endpoints: List<String> = emptyList()): de.moritzf.quota.shared.NativePdfRoute {
    if (endpoints.any { it == "/v1/messages" || it == "/messages" }) return de.moritzf.quota.shared.NativePdfRoute.ANTHROPIC
    if (endpoints.any { it.endsWith("/responses") || it == "/responses" }) return de.moritzf.quota.shared.NativePdfRoute.RESPONSES
    if (endpoints.any { it.endsWith("/chat/completions") || it == "/chat/completions" }) return de.moritzf.quota.shared.NativePdfRoute.CHAT
    return when {
        isClaudeModel(modelId) -> de.moritzf.quota.shared.NativePdfRoute.ANTHROPIC
        shouldUseResponsesApi(modelId) -> de.moritzf.quota.shared.NativePdfRoute.RESPONSES
        else -> de.moritzf.quota.shared.NativePdfRoute.CHAT
    }
}

internal fun githubCopilotHeaders(token: String): Map<String, String> = mapOf(
    "Authorization" to "Bearer $token",
    "Accept" to "application/json",
    "User-Agent" to GitHubCopilotProxyIds.USER_AGENT,
    "Copilot-Integration-Id" to GitHubCopilotProxyIds.COPILOT_INTEGRATION_ID,
    "Editor-Version" to GitHubCopilotProxyIds.EDITOR_VERSION,
    "Editor-Plugin-Version" to GitHubCopilotProxyIds.EDITOR_PLUGIN_VERSION,
    "X-GitHub-Api-Version" to GitHubCopilotProxyIds.API_VERSION,
    "Openai-Intent" to "conversation-edits",
    "x-initiator" to "user",
)

internal fun fetchGitHubListedModels(base: java.net.URI, token: String, httpClient: HttpClient = HttpClient.newHttpClient()): List<GitHubListedModel> {
    val request = HttpRequest.newBuilder(java.net.URI.create(UrlResolver.resolveTargetUrl("/models", base.toString())))
        .timeout(Duration.ofSeconds(30))
        .GET()
    githubCopilotHeaders(token).forEach { (name, value) -> request.header(name, value) }
    val response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() !in 200..299) return emptyList()
    return parseGitHubListedModels(response.body())
}

private fun mediaTypes(capabilities: JsonObject?): List<String> {
    val vision = capabilities?.jsonObject("limits")?.jsonObject("vision") ?: return emptyList()
    val types = vision["supported_media_types"] as? JsonArray ?: return emptyList()
    return types.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
}

internal fun routeForStorageValue(value: String?): SubscriptionProxyRoute? {
    return SubscriptionProxyRoute.entries.firstOrNull { route ->
        value == route.normalizedPath || value == route.upstreamPath || value == "/v1${route.normalizedPath}"
    }
}

internal fun shouldUseResponsesApi(modelId: String): Boolean {
    // Copilot Grok is responses-only. Junie speaks chat, so bridge it the same way as MAI.
    if (modelId.startsWith("mai-code-") || modelId.startsWith("grok-")) {
        return true
    }
    val major = GPT_MAJOR_REGEX.find(modelId)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return false
    return major >= 5 && !modelId.startsWith("gpt-5-mini")
}

internal fun shouldBridgeResponsesModel(modelId: String): Boolean = shouldUseResponsesApi(modelId)

internal fun isClaudeModel(modelId: String): Boolean = modelId.startsWith("claude-")

internal fun containsImageInput(element: JsonElement?): Boolean {
    return when (element) {
        is JsonObject -> {
            val type = (element["type"] as? JsonPrimitive)?.contentOrNull
            type == "image_url" || type == "input_image" || element.values.any(::containsImageInput)
        }

        is JsonArray -> element.any(::containsImageInput)
        else -> false
    }
}

internal data class RemoteImageHop(
    val statusCode: Int,
    val uri: URI,
    val location: String?,
    val contentType: String?,
    val body: ByteArray,
)

internal object SafeRemoteImageFetcher {
    const val MAX_REDIRECTS = 5

    fun get(
        url: String,
        send: (URI) -> RemoteImageHop?,
        isSafe: (URI) -> Boolean,
    ): RemoteImageHop? {
        var current = runCatching { URI.create(url) }.getOrNull() ?: return null
        repeat(MAX_REDIRECTS + 1) {
            if (!isSafe(current)) return null
            val hop = send(current) ?: return null
            if (hop.statusCode in 200..<300) {
                return hop.takeIf { isSafe(it.uri) }
            }
            if (hop.statusCode !in 300..399) return null
            val location = hop.location?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            current = current.resolve(location)
        }
        return null
    }
}
