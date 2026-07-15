package de.moritzf.quota.openai.proxy

import de.moritzf.proxy.auth.CredentialsProvider
import de.moritzf.proxy.config.ServerConfig
import de.moritzf.proxy.model.CodexClientVersionResolver
import de.moritzf.proxy.model.ModelResolver
import de.moritzf.proxy.server.ApiKeyStore
import de.moritzf.proxy.server.ProxyServer
import de.moritzf.proxy.transport.CodexHttpClient
import de.moritzf.proxy.usage.UsageTracker
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
    // Invoked when the proxy sees an upstream 401, with the rejected access token. The
    // embedder owns the actual refresh (e.g. the IDE's secure-storage auth service); the
    // proxy only signals that a refresh is needed. Returns the access token in effect
    // after the refresh, or null when refreshing failed or is unsupported (default).
    private val tokenRefresher: (staleAccessToken: String?) -> String? = { null },
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build(),
    private val upstreamBaseUri: URI = DEFAULT_UPSTREAM_BASE_URI,
    private val debugLogger: ((String) -> Unit)? = null,
    private val fullRequestLogging: Boolean = false,
    private val requestLogDir: String = REQUEST_LOG_DIR,
    private val codexVersionProvider: () -> String = { CodexClientVersionResolver.resolve(null) },
    private val allowAnyCors: Boolean = false,
    private val allowedCorsOrigins: List<String> = emptyList(),
    // Per-request access lines on stdout; useful for the standalone console proxy,
    // disabled when embedded in the IDE.
    private val consoleAccessLog: Boolean = false,
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
            val models = ADVERTISED_MODELS
            val config = serverConfig(localApiKey, models)
            val credentialsProvider = QuotaCodexCredentialsProvider(
                accessTokenProvider,
                accountIdProvider,
                tokenRefresher,
                codexVersionProvider,
            )
            val client = SanitizingCodexHttpClient(config, httpClient, credentialsProvider)
            val proxyServer = ProxyServer(
                config,
                client,
                ModelResolver(client, models, null),
                UsageTracker(),
                ApiKeyStore(mapOf(localApiKey to LOCAL_KEY_NAME), null, null),
            )
            proxyServer.start()
            server = proxyServer
            debugLog("AIProxyOauth proxy started at http://127.0.0.1:$port")
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
            allowAnyCors,
            allowedCorsOrigins,
            fullRequestLogging,
            requestLogDir,
            false,
            ServerConfig.DEFAULT_CODEX_INSTRUCTIONS_MODE,
            null,
            // The Responses replay cache emulates previous_response_id/item_reference for
            // store=false. Junie always inlines full history, so it is pure overhead here.
            false,
            consoleAccessLog,
        )
    }

    private fun debugLog(message: String) {
        debugLogger?.invoke(message)
    }

    private class SanitizingCodexHttpClient(
        config: ServerConfig,
        httpClient: HttpClient,
        credentialsProvider: CredentialsProvider,
    ) : CodexHttpClient(config, httpClient, credentialsProvider) {
        @Throws(Exception::class)
        override fun request(
            path: String,
            method: String?,
            body: String?,
            extraHeaders: Map<String, String>?,
        ): HttpResponse<InputStream> {
            return super.request(path, method, sanitizeResponsesBody(path, body), extraHeaders)
        }

        @Throws(Exception::class)
        override fun request(
            path: String,
            method: String?,
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
            method: String?,
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
        // Curated Codex models for /v1/models and /v1/model/info.
        // Align with the Codex UI menu for ChatGPT subscriptions, using models.json
        // visibility/priority as a guide (not the incomplete ChatGPT /models endpoint).
        // gpt-5.2 is still marked list upstream but is no longer a useful ChatGPT-subscription
        // choice; omit it. gpt-5.5-pro stays absent (backend rejects ChatGPT accounts).
        // Hidden/legacy slugs still work via fallbackModel if a client requests them.
        private val ADVERTISED_BASE_MODELS = listOf(
            "gpt-5.6-sol",
            "gpt-5.6-terra",
            "gpt-5.6-luna",
            "gpt-5.5",
        )

        // Reasoning tiers from models.json. Junie picks tier via "<base> (<level>)" model
        // names; other clients send bare base + reasoning_effort.
        private val GPT56_REASONING_TIERS = listOf("low", "medium", "high", "xhigh", "max", "ultra")
        private val GPT56_LUNA_REASONING_TIERS = listOf("low", "medium", "high", "xhigh", "max")
        private val LEGACY_REASONING_TIERS = listOf("low", "medium", "high", "xhigh")
        private val MINI_REASONING_TIERS = listOf("medium", "high")

        private val ADVERTISED_MODELS: List<String> = ADVERTISED_BASE_MODELS.flatMap { base ->
            val tiers = reasoningTiersFor(base)
            listOf(base) + tiers.map { tier -> "$base ($tier)" }
        }

        private fun reasoningTiersFor(base: String): List<String> {
            return when {
                base.endsWith("-mini") -> MINI_REASONING_TIERS
                base == "gpt-5.6-luna" -> GPT56_LUNA_REASONING_TIERS
                base.startsWith("gpt-5.6") -> GPT56_REASONING_TIERS
                else -> LEGACY_REASONING_TIERS
            }
        }

        fun advertisedModels(): List<String> = ADVERTISED_MODELS

        /** Flagship GPT-5.6 Sol when present; otherwise alphabetically latest base id. */
        fun defaultAdvertisedModel(): String =
            ADVERTISED_BASE_MODELS.firstOrNull { it == "gpt-5.6-sol" }
                ?: ADVERTISED_BASE_MODELS.maxOrNull()
                ?: ServerConfig.DEFAULT_MODEL
    }
}
