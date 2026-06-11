package de.moritzf.quota.openai.proxy

import com.aiproxyoauth.auth.AuthManager
import com.aiproxyoauth.auth.AuthRequiredException
import com.aiproxyoauth.config.ServerConfig
import com.aiproxyoauth.model.ModelResolver
import com.aiproxyoauth.server.ApiKeyStore
import com.aiproxyoauth.server.ProxyServer
import com.aiproxyoauth.transport.CodexHttpClient
import com.aiproxyoauth.usage.UsageTracker
import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.shared.JsonSupport
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject

/**
 * Loopback-only OpenAI-compatible proxy backed by AIProxyOauth.
 */
class OpenAiProxyServer(
    private val port: Int,
    private val localApiKeyProvider: () -> String?,
    private val accessTokenProvider: () -> String?,
    private val accountIdProvider: () -> String?,
    private val quotaProvider: () -> OpenAiCodexQuota? = { null },
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build(),
    private val upstreamBaseUri: URI = DEFAULT_UPSTREAM_BASE_URI,
    @Suppress("UNUSED_PARAMETER")
    private val stripV1Prefix: Boolean = true,
    private val debugLogger: ((String) -> Unit)? = null,
    private val fullRequestLogging: Boolean = false,
    private val requestLogDir: String = REQUEST_LOG_DIR,
) {
    private val running = AtomicBoolean(false)
    private var server: ProxyServer? = null

    val isRunning: Boolean
        get() = running.get()

    fun start() {
        check(running.compareAndSet(false, true)) { "OpenAI proxy is already running" }

        val localApiKey = localApiKeyProvider()?.takeIf { it.isNotBlank() }
        if (localApiKey == null) {
            running.set(false)
            error("OpenAI proxy local API key is missing")
        }

        try {
            val models = modelsForPlan(quotaProvider()?.planType)
            val config = serverConfig(localApiKey, models)
            val authManager = ProviderAuthManager(config, httpClient, accessTokenProvider, accountIdProvider)
            val client = SanitizingCodexHttpClient(config, httpClient, authManager)
            val proxyServer = ProxyServer(
                config,
                client,
                ModelResolver(client, models, null),
                UsageTracker(),
                ApiKeyStore(mapOf(localApiKey to LOCAL_KEY_NAME), null, null),
            )
            proxyServer.start()
            server = proxyServer
            debugLog("AIProxyOauth proxy started at http://127.0.0.1:$port/v1")
        } catch (exception: Exception) {
            running.set(false)
            server = null
            throw exception
        }
    }

    fun stop() {
        val current = server
        server = null
        running.set(false)
        current?.stop()
    }

    private fun serverConfig(localApiKey: String, models: List<String>): ServerConfig {
        return ServerConfig(
            HOST,
            port,
            models,
            null,
            upstreamBaseUri.toString(),
            ServerConfig.DEFAULT_CLIENT_ID,
            null,
            null,
            DEFAULT_CODEX_INSTRUCTIONS,
            false,
            mapOf(localApiKey to LOCAL_KEY_NAME),
            null,
            true,
            emptyList(),
            fullRequestLogging,
            requestLogDir,
            false,
            ServerConfig.DEFAULT_CODEX_INSTRUCTIONS_MODE,
            null,
        )
    }

    private fun debugLog(message: String) {
        debugLogger?.invoke(message)
    }

    private class ProviderAuthManager(
        config: ServerConfig,
        httpClient: HttpClient,
        private val accessTokenProvider: () -> String?,
        private val accountIdProvider: () -> String?,
    ) : AuthManager(config, httpClient) {
        @Throws(Exception::class)
        override fun getAuthHeaders(): Map<String, String> {
            val accessToken = accessTokenProvider()?.trim().takeUnless { it.isNullOrBlank() }
                ?: throw AuthRequiredException("OpenAI login required: log in on the OpenAI settings tab, then retry.")
            val headers = linkedMapOf(
                "Authorization" to "Bearer $accessToken",
                "OpenAI-Beta" to "responses=experimental",
            )
            val accountId = accountIdProvider()?.trim().takeUnless { it.isNullOrBlank() }
            if (accountId != null) {
                headers["chatgpt-account-id"] = accountId
            }
            return headers
        }
    }

    private class SanitizingCodexHttpClient(
        config: ServerConfig,
        httpClient: HttpClient,
        authManager: AuthManager,
    ) : CodexHttpClient(config, httpClient, authManager) {
        @Throws(Exception::class)
        override fun request(
            path: String,
            method: String,
            body: String?,
            extraHeaders: Map<String, String>?,
        ): HttpResponse<InputStream> {
            return super.request(path, method, sanitizeResponsesBody(path, body), extraHeaders)
        }

        @Throws(Exception::class)
        override fun request(
            path: String,
            method: String,
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
            method: String,
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
        @JvmField
        val DEFAULT_UPSTREAM_BASE_URI: URI = URI.create("https://chatgpt.com/backend-api/codex")

        private const val HOST = "127.0.0.1"
        private const val LOCAL_KEY_NAME = "local"
        private const val DEFAULT_CODEX_INSTRUCTIONS = "You are a coding assistant."
        private val REQUEST_LOG_DIR = System.getProperty("java.io.tmpdir") + "/openai-usage-quota-intellij/openai-proxy-requests"
        private val CODEX_OVERRIDDEN_RESPONSE_FIELDS = setOf("store", "stream")
        private val CODEX_UNSUPPORTED_RESPONSE_FIELDS = setOf(
            "max_output_tokens",
            "temperature",
        )
        private val PAID_MODELS = listOf(
            "gpt-5.4",
            "gpt-5.4-mini",
            "gpt-5.5",
            "gpt-5.5-pro",
        )

        private fun modelsForPlan(@Suppress("UNUSED_PARAMETER") planType: String?): List<String> {
            return PAID_MODELS
        }
    }
}
