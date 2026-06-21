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
        // The curated set of Codex models exposed via /v1/models and /v1/model/info.
        // Per-account entitlement is enforced by the Codex backend, not here: selecting a
        // model the subscription lacks returns an upstream error that the proxy surfaces
        // as `insufficient_quota`. The live Codex /models endpoint is not complete enough
        // for this purpose: it has omitted GPT-5.5 while the Codex UI still exposes it.
        // Keep this list aligned with the Codex UI model menu.
        // gpt-5.5-pro is intentionally absent: the Codex backend rejects it for ChatGPT
        // accounts ("not supported when using Codex with a ChatGPT account").
        private val ADVERTISED_BASE_MODELS = listOf(
            "gpt-5.5",
            "gpt-5.4",
            "gpt-5.4-mini",
            "gpt-5.3-codex-spark",
        )

        // Reasoning tiers the Codex backend accepts for the advertised non-mini models
        // (verified against the live backend). `minimal` is excluded — upstream rejects
        // it — and `none` is omitted because it is not one of the levels clients surface.
        // Junie derives its reasoning-tier selector from model NAMES of the form
        // "<base> (<level>)"; opencode and other clients ignore the suffixed entries and
        // send the bare base model plus a separate reasoning-effort option instead.
        private val REASONING_TIERS = listOf("low", "medium", "high", "xhigh")
        private val MINI_REASONING_TIERS = listOf("medium", "high")

        private val ADVERTISED_MODELS: List<String> = ADVERTISED_BASE_MODELS.flatMap { base ->
            val tiers = if (base.endsWith("-mini")) MINI_REASONING_TIERS else REASONING_TIERS
            listOf(base) + tiers.map { tier -> "$base ($tier)" }
        }

        fun advertisedModels(): List<String> = ADVERTISED_MODELS

        fun defaultAdvertisedModel(): String = ADVERTISED_BASE_MODELS.maxOrNull() ?: ServerConfig.DEFAULT_MODEL
    }
}
