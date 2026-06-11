package de.moritzf.quota.openai.proxy

import com.aiproxyoauth.auth.AuthRequiredException
import com.aiproxyoauth.auth.CredentialsProvider
import com.aiproxyoauth.config.ServerConfig
import com.aiproxyoauth.model.ModelResolver
import com.aiproxyoauth.server.ApiKeyStore
import com.aiproxyoauth.server.ProxyServer
import com.aiproxyoauth.transport.CodexHttpClient
import com.aiproxyoauth.usage.UsageTracker
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
            val credentialsProvider = QuotaCredentialsProvider(accessTokenProvider, accountIdProvider, tokenRefresher)
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
            true,
            emptyList(),
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

    /**
     * Pure delegating [CredentialsProvider]: it never refreshes or persists tokens itself.
     * Header tokens come from the IDE auth service (which transparently refreshes on local
     * expiry), and an upstream 401 is routed back to that service via [tokenRefresher] so
     * the IDE's secure-storage login/refresh logic remains the sole owner of credentials.
     */
    private class QuotaCredentialsProvider(
        private val accessTokenProvider: () -> String?,
        private val accountIdProvider: () -> String?,
        private val tokenRefresher: (staleAccessToken: String?) -> String?,
    ) : CredentialsProvider {
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

        override fun refreshAfterUnauthorized(rejectedAuthorizationHeader: String?): Boolean {
            // Key the refresh on the exact token the rejected request carried — not on any
            // shared mutable slot — so concurrent 401s on different tokens dedupe correctly.
            val staleToken = rejectedAuthorizationHeader
                ?.removePrefix("Bearer ")?.trim()?.takeUnless { it.isEmpty() }
            val tokenAfterRefresh = tokenRefresher(staleToken)
            return tokenAfterRefresh != null && tokenAfterRefresh != staleToken
        }
    }

    private class SanitizingCodexHttpClient(
        config: ServerConfig,
        httpClient: HttpClient,
        credentialsProvider: CredentialsProvider,
    ) : CodexHttpClient(config, httpClient, credentialsProvider) {
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
        // The curated set of Codex models exposed via /v1/models and /v1/model/info.
        // Per-account entitlement is enforced by the Codex backend, not here: selecting a
        // model the subscription lacks returns an upstream error that the proxy surfaces
        // as `insufficient_quota`. We advertise a static list rather than live-discovering
        // so Junie's model-discovery call stays instant and offline-tolerant.
        private val ADVERTISED_MODELS = listOf(
            "gpt-5.4",
            "gpt-5.4-mini",
            "gpt-5.5",
            "gpt-5.5-pro",
        )
    }
}
