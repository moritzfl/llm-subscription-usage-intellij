package de.moritzf.quota.idea.auth

import com.intellij.openapi.diagnostic.Logger
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.intellij.lang.annotations.Language
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Runs the browser-based OAuth login flow including local callback server handling.
 */
class OAuthLoginFlow private constructor(
    private val config: OAuthClientConfig,
    val codeVerifier: String,
    private val state: String,
    val authorizationUrl: String,
) {
    private val callbackDeferred = CompletableDeferred<OAuthCallbackResult>()
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        LOG.warn("OAuth server coroutine failed", exception)
        if (!callbackDeferred.isCompleted) {
            val details = exception.message?.takeIf { it.isNotBlank() } ?: exception::class.java.simpleName
            callbackDeferred.complete(OAuthCallbackResult(error = "Login server failed: $details"))
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private var server: HttpServer? = null
    private var serverExecutor: ExecutorService? = null
    private val callbackPath: String = URI.create(config.redirectUri).path.takeIf { it.isNotBlank() } ?: "/auth/callback"
    val expectedState: String get() = state
    val usesLocalCallbackServer: Boolean get() = config.callbackMode == OAuthCallbackMode.LOOPBACK

    suspend fun waitForCallback(): OAuthCallbackResult {
        return try {
            withTimeoutOrNull(CALLBACK_TIMEOUT_MS) {
                callbackDeferred.await()
            } ?: OAuthCallbackResult(error = "Authentication timed out")
        } finally {
            scheduleStopServer()
        }
    }

    /**
     * Accepts a pasted Claude/Anthropic callback value.
     * Returns null on success (flow continues to token exchange).
     * Returns an error string if the paste is invalid; the login stays open for retry.
     * Returns a terminal error only if the flow is no longer waiting.
     */
    fun completeWithPastedCallback(input: String): String? {
        if (callbackDeferred.isCompleted) {
            return "No login in progress"
        }
        val parsed = parseCallbackInput(input)
            ?: return "Could not parse authorization code. Paste the full callback URL or code#state."
        if (parsed.state != state) {
            return "State mismatch. Start login again and paste the code from that browser session."
        }
        if (parsed.code.isBlank()) {
            return "Missing authorization code"
        }
        callbackDeferred.complete(OAuthCallbackResult(code = parsed.code, state = parsed.state))
        return null
    }

    fun stopServerNow() {
        stopServer()
    }

    fun cancel(reason: String) {
        if (!callbackDeferred.isCompleted) {
            callbackDeferred.complete(OAuthCallbackResult(error = reason))
        }
        stopServer()
    }

    private fun startServer() {
        if (!usesLocalCallbackServer) {
            return
        }
        try {
            val engine = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), config.callbackPort), 0)
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable).apply { isDaemon = true }
            }
            engine.executor = executor
            serverExecutor = executor
            engine.createContext("/auth/ping") { exchange ->
                handlePing(exchange)
            }
            engine.createContext(callbackPath) { exchange ->
                handleCallback(exchange)
            }
            engine.start()
            server = engine
        } catch (exception: Exception) {
            LOG.warn("Failed to bind OAuth callback server to ${config.redirectUri}", exception)
            throw exception
        }
        LOG.info("OAuth callback server started at ${config.redirectUri}")
    }

    private fun handlePing(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            sendResponse(exchange, 405, "Method Not Allowed", "text/plain; charset=utf-8")
            return
        }
        sendResponse(exchange, 200, "OK", "text/plain; charset=utf-8")
    }

    private fun handleCallback(exchange: HttpExchange) {
        val responseText = try {
            if (exchange.requestMethod != "GET") {
                sendResponse(exchange, 405, "Method Not Allowed", "text/plain; charset=utf-8")
                return
            }
            val remoteAddress = exchange.remoteAddress?.address
            if (remoteAddress == null || !remoteAddress.isLoopbackAddress) {
                LOG.warn("Rejected non-loopback callback from ${exchange.remoteAddress}")
                sendResponse(exchange, 403, "", "text/plain; charset=utf-8")
                return
            }

            val params = OAuthUrlCodec.parseQuery(exchange.requestURI.rawQuery)
            val error = params["error"]
            when {
                error != null -> {
                    callbackDeferred.complete(OAuthCallbackResult(error = "OAuth error: $error"))
                    buildHtmlResponse("Authentication Failed", "Authentication failed: $error", false)
                }

                params["code"].isNullOrBlank() || params["state"].isNullOrBlank() -> {
                    LOG.warn("Callback missing code/state")
                    callbackDeferred.complete(OAuthCallbackResult(error = "Missing code or state"))
                    buildHtmlResponse("Authentication Failed", "Missing code/state parameters.", false)
                }

                params["state"] != state -> {
                    LOG.warn("Callback state mismatch")
                    callbackDeferred.complete(OAuthCallbackResult(error = "State mismatch"))
                    buildHtmlResponse("Authentication Failed", "State mismatch.", false)
                }

                else -> {
                    val code = params["code"]!!
                    LOG.info("Callback completed with authorization code")
                    callbackDeferred.complete(OAuthCallbackResult(code = code, state = params["state"]))
                    buildHtmlResponse(
                        "Authentication Successful",
                        "You can close this window and return to the IDE.",
                        true,
                    )
                }
            }
        } catch (exception: Exception) {
            LOG.warn("Callback handling failed", exception)
            val details = exception.message?.takeIf { it.isNotBlank() } ?: "Internal callback handler error"
            callbackDeferred.complete(OAuthCallbackResult(error = details))
            buildHtmlResponse("Authentication Failed", "Authentication failed.", false)
        }

        sendResponse(exchange, 200, responseText, "text/html; charset=utf-8")
    }

    private fun sendResponse(exchange: HttpExchange, status: Int, body: String, contentType: String) {
        try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", contentType)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { output ->
                output.write(bytes)
            }
        } finally {
            exchange.close()
        }
    }

    private fun stopServer() {
        val currentServer = server
        val currentExecutor = serverExecutor
        if (currentServer != null) {
            try {
                currentServer.stop(0)
                LOG.info("OAuth callback server stopped")
            } catch (exception: Exception) {
                LOG.warn("Failed to stop OAuth callback server cleanly", exception)
            }
        }
        server = null
        currentExecutor?.shutdownNow()
        serverExecutor = null
        scope.cancel()
    }

    private fun scheduleStopServer() {
        scope.launch {
            delay(CALLBACK_SHUTDOWN_DELAY_MS)
            stopServer()
        }
    }

    companion object {
        private val LOG = Logger.getInstance(OAuthLoginFlow::class.java)
        private const val CALLBACK_TIMEOUT_MS: Long = 10 * 60 * 1000L
        private const val CALLBACK_SHUTDOWN_DELAY_MS: Long = 3 * 1000L

        @JvmStatic
        fun start(config: OAuthClientConfig): OAuthLoginFlow {
            val verifier = generateCodeVerifier()
            val challenge = generateCodeChallenge(verifier)
            val state = generateState()
            val authorizationUrl = buildAuthorizationUrl(config, challenge, state)
            return OAuthLoginFlow(config, verifier, state, authorizationUrl).also { it.startServer() }
        }

        @JvmStatic
        fun parseQuery(query: String?): Map<String, String> = OAuthUrlCodec.parseQuery(query)

        @JvmStatic
        fun parseUri(value: String, redirectUri: String): URI = OAuthUrlCodec.parseCallbackUri(value, redirectUri)

        @JvmStatic
        fun parseCallbackInput(input: String?): ParsedOAuthCallback? {
            val trimmed = input?.trim()?.trim('"', '\'')?.orEmpty().orEmpty()
            if (trimmed.isEmpty()) {
                return null
            }

            // Full URL with query or fragment: ?code=&state= or #code=&state=
            runCatching {
                val uri = URI.create(trimmed.replace(' ', '+'))
                val candidates = listOfNotNull(uri.rawQuery, uri.rawFragment, uri.fragment)
                for (candidate in candidates) {
                    parseCodeStatePair(candidate)?.let { return it }
                    // Fragment may itself be URL-encoded: code%3D...%26state%3D...
                    val decoded = runCatching {
                        java.net.URLDecoder.decode(candidate, Charsets.UTF_8)
                    }.getOrNull()
                    if (!decoded.isNullOrBlank() && decoded != candidate) {
                        parseCodeStatePair(decoded)?.let { return it }
                    }
                }
            }

            // Legacy Claude form: code#state (no equals signs)
            val hashSplits = trimmed.split('#', limit = 2)
            if (hashSplits.size == 2 &&
                hashSplits[0].isNotBlank() &&
                hashSplits[1].isNotBlank() &&
                !hashSplits[0].contains('=') &&
                !hashSplits[1].contains('=') &&
                !hashSplits[0].contains("://")
            ) {
                return ParsedOAuthCallback(code = hashSplits[0], state = hashSplits[1])
            }

            // Bare query string or code=...&state=...
            parseCodeStatePair(trimmed)?.let { return it }
            val decoded = runCatching {
                java.net.URLDecoder.decode(trimmed, Charsets.UTF_8)
            }.getOrNull()
            if (!decoded.isNullOrBlank() && decoded != trimmed) {
                parseCodeStatePair(decoded)?.let { return it }
            }
            return null
        }

        private fun parseCodeStatePair(value: String?): ParsedOAuthCallback? {
            if (value.isNullOrBlank()) return null
            val params = parseQuery(value)
            val code = params["code"]?.takeIf { it.isNotBlank() } ?: return null
            val state = params["state"]?.takeIf { it.isNotBlank() } ?: return null
            return ParsedOAuthCallback(code = code, state = state)
        }

        private fun buildAuthorizationUrl(config: OAuthClientConfig, challenge: String, state: String): String {
            // Claude/Anthropic is picky about authorize URL shape (param order + %20 spaces).
            // Match the known-working Claude Code / opencode-anthropic-auth order exactly.
            val params = if (config.callbackMode == OAuthCallbackMode.PASTE) {
                linkedMapOf(
                    "code" to "true",
                    "client_id" to config.clientId,
                    "response_type" to "code",
                    "redirect_uri" to config.redirectUri,
                    "scope" to config.scopes,
                    "code_challenge" to challenge,
                    "code_challenge_method" to "S256",
                    "state" to state,
                )
            } else {
                linkedMapOf(
                    "client_id" to config.clientId,
                    "redirect_uri" to config.redirectUri,
                    "scope" to config.scopes,
                    "code_challenge" to challenge,
                    "code_challenge_method" to "S256",
                    "response_type" to "code",
                    "state" to state,
                ).also { map ->
                    if (config.includeNonce) {
                        map["nonce"] = generateState()
                    }
                    map.putAll(config.extraParameters)
                }
            }
            return "${config.authorizationEndpoint}?${OAuthUrlCodec.queryEncode(params)}"
        }

        private fun generateCodeVerifier(): String {
            val random = ByteArray(64)
            SecureRandom().nextBytes(random)
            return base64Url(random)
        }

        private fun generateCodeChallenge(verifier: String): String {
            return try {
                val digest = MessageDigest.getInstance("SHA-256")
                base64Url(digest.digest(verifier.toByteArray(Charsets.UTF_8)))
            } catch (exception: Exception) {
                throw IllegalStateException("Unable to create code challenge", exception)
            }
        }

        private fun generateState(): String {
            // Claude/Anthropic auth uses UUID hex without dashes (32 chars).
            val random = ByteArray(16)
            SecureRandom().nextBytes(random)
            return random.joinToString("") { "%02x".format(it) }
        }

        @OptIn(ExperimentalEncodingApi::class)
        private fun base64Url(value: ByteArray): String {
            return Base64.UrlSafe
                .withPadding(Base64.PaddingOption.ABSENT)
                .encode(value)
        }

        private fun buildHtmlResponse(title: String, message: String, success: Boolean): String {
            val background = if (success) "#0d8f6f" else "#b3282d"
            @Language("HTML")
            val response = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <title>%s</title>
                <style>
                  body {
                    font-family: Arial,serif;
                    display: flex;
                    justify-content: center;
                    align-items: center;
                    height: 100vh;
                    margin: 0;
                    background: %s;
                    color: white;
                  }
                  .container {
                    text-align: center;
                    padding: 2rem;
                  }
                  h1 { font-size: 1.6rem; margin-bottom: 0.5rem; }
                  p { opacity: 0.9; }
                </style>
                </head>
                <body>
                <div class="container">
                  <h1>%s</h1>
                  <p>%s</p>
                </div>
                </body>
                </html>
            """.trimIndent()
            return response.format(title, background, title, message)
        }
    }
}
