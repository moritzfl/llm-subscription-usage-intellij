package com.aiproxyoauth.model

import com.aiproxyoauth.transport.CodexHttpClient
import com.aiproxyoauth.util.CollectionUtils
import com.aiproxyoauth.util.Json
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.locks.ReentrantLock

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

    @Throws(Exception::class)
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

    @Throws(Exception::class)
    private fun fetchAvailableModels(): List<String> {
        val clientVersion = resolveCodexClientVersion()
        val path = "/models?client_version=" + URLEncoder.encode(clientVersion, StandardCharsets.UTF_8)

        val response = client.requestString(path, "GET", null, null)

        if (response.statusCode() !in 200..<300) {
            val message = extractUpstreamError(response.body())
            throw RuntimeException(message ?: "Failed to load models from Codex.")
        }

        val parsed = Json.MAPPER.readTree(response.body())
        val modelsNode = parsed.get("models")
        if (modelsNode == null || !modelsNode.isArray) {
            throw RuntimeException("Codex returned a malformed models response.")
        }

        var models = modelsNode.mapNotNull { model ->
            val slug = model.get("slug")
            if (slug != null && slug.isTextual && slug.asText().isNotEmpty()) {
                slug.asText()
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
                val parsed = Json.MAPPER.readTree(bodyText)
                val detail = parsed.get("detail")
                if (detail != null && detail.isTextual && detail.asText().isNotEmpty()) {
                    return detail.asText()
                }
                val error = parsed.get("error")
                if (error != null && error.isObject) {
                    val message = error.get("message")
                    if (message != null && message.isTextual) {
                        return message.asText()
                    }
                }
            } catch (_: Exception) {
            }
            return bodyText
        }
    }
}
