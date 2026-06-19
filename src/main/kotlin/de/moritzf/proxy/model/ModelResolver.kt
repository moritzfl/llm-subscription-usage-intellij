package de.moritzf.proxy.model

import de.moritzf.proxy.server.booleanPath
import de.moritzf.proxy.server.hasKey
import de.moritzf.proxy.server.isObject
import de.moritzf.proxy.server.isTextual
import de.moritzf.proxy.server.pathOrNull
import de.moritzf.proxy.transport.CodexHttpClient
import de.moritzf.proxy.util.CollectionUtils
import de.moritzf.proxy.util.Json
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.locks.ReentrantLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ModelResolver(
    private val client: CodexHttpClient,
    private val configuredModels: List<String>?,
    private val codexVersion: String?,
) {
    @Volatile
    private var cachedModels: List<String>? = null

    @Volatile
    private var modelsCacheExpiresAt = 0L

    private val modelsLock = ReentrantLock()

    fun resolveModels(): List<String> {
        if (!configuredModels.isNullOrEmpty()) {
            return CollectionUtils.uniqueStrings(configuredModels)
        }

        val now = System.currentTimeMillis()
        var cached = cachedModels
        if (cached != null && now < modelsCacheExpiresAt) {
            return ArrayList(cached)
        }

        modelsLock.lock()
        try {
            cached = cachedModels
            if (cached != null && System.currentTimeMillis() < modelsCacheExpiresAt) {
                return ArrayList(cached)
            }

            val models = fetchAvailableModels()
            cachedModels = models
            modelsCacheExpiresAt = System.currentTimeMillis() + MODELS_CACHE_TTL_MS
            return ArrayList(models)
        } finally {
            modelsLock.unlock()
        }
    }

    fun resolveCodexClientVersion(): String = CodexClientVersionResolver.resolve(codexVersion)

    private fun fetchAvailableModels(): List<String> {
        val clientVersion = resolveCodexClientVersion()
        val path = "/models?client_version=" + URLEncoder.encode(clientVersion, StandardCharsets.UTF_8)
        val response = client.requestString(path, "GET", null, null)

        if (response.statusCode() !in 200..<300) {
            val message = extractUpstreamError(response.body())
            throw RuntimeException(message ?: "Failed to load models from Codex.")
        }

        val parsed = Json.INSTANCE.parseToJsonElement(response.body()) as? JsonObject
            ?: throw RuntimeException("Codex returned a malformed models response.")
        val modelsNode = parsed["models"]
        if (modelsNode !is JsonArray) {
            throw RuntimeException("Codex returned a malformed models response.")
        }

        var models = modelsNode.mapNotNull { model ->
            val slug = (model as? JsonObject)?.get("slug")
            if (slug is JsonPrimitive && slug.isString && slug.content.isNotEmpty()) {
                slug.content
            } else {
                null
            }
        }

        models = CollectionUtils.uniqueStrings(models)
        if (models.isEmpty()) {
            throw RuntimeException("Codex returned an empty models list.")
        }

        return models
    }

    companion object {
        private const val MODELS_CACHE_TTL_MS = 5 * 60 * 1000L

        private fun extractUpstreamError(bodyText: String?): String? {
            if (bodyText.isNullOrEmpty()) {
                return null
            }
            try {
                val parsed = Json.INSTANCE.parseToJsonElement(bodyText) as? JsonObject ?: return bodyText
                val detail = parsed["detail"]
                if (detail is JsonPrimitive && detail.isString && detail.content.isNotEmpty()) {
                    return detail.content
                }
                val error = parsed["error"]
                if (error is JsonObject) {
                    val message = error["message"]
                    if (message is JsonPrimitive && message.isString) {
                        return message.content
                    }
                }
            } catch (_: Exception) {
            }
            return bodyText
        }
    }
}