package de.moritzf.quota.idea.auth

import com.intellij.openapi.diagnostic.Logger
import io.ktor.server.application.Application
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.host
import io.ktor.server.request.queryString
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.intellij.lang.annotations.Language
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val callbackDeferred = CompletableDeferred<OAuthCallbackResult>()
    private var server: EmbeddedServer<*, *>? = null

    suspend fun waitForCallback(): OAuthCallbackResult {
        return try {
            withTimeoutOrNull(CALLBACK_TIMEOUT_MS) {
                callbackDeferred.await()
            } ?: OAuthCallbackResult(error = "Authentication timed out")
        } finally {
            scheduleStopServer()
        }
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
        try {
            val module: Application.() -> Unit = {
                routing {
                    get("/auth/ping") {
                        call.respondText("OK")
                    }
                    get("/auth/callback") {
                        handleCallback(
                            remoteHost = call.request.host(),
                            query = call.request.queryString(),
                            respond = { status, responseText ->
                                call.respondText(responseText, ContentType.Text.Html, status)
                            },
                        )
                    }
                }
            }
            val engine = embeddedServer(CIO, host = "localhost", port = config.callbackPort, module = module)
            engine.start(wait = false)
            server = engine
        } catch (exception: Exception) {
            LOG.warn("Failed to bind OAuth callback server to ${config.redirectUri}", exception)
            throw exception
        }
        LOG.info("OAuth callback server started at ${config.redirectUri}")
    }

    private suspend fun handleCallback(
        remoteHost: String,
        query: String,
        respond: suspend (HttpStatusCode, String) -> Unit,
    ) {
        if (remoteHost !in setOf("127.0.0.1", "localhost", "::1")) {
            LOG.warn("Rejected non-loopback callback from $remoteHost")
            respond(HttpStatusCode.Forbidden, "")
            return
        }

        val responseText = try {
            val params = OAuthUrlCodec.parseQuery(query)
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
                    callbackDeferred.complete(OAuthCallbackResult(code = code))
                    buildHtmlResponse(
                        "Authentication Successful",
                        "You can close this window and return to the IDE.",
                        true,
                    )
                }
            }
        } catch (exception: Exception) {
            LOG.warn("Callback handling failed", exception)
            callbackDeferred.complete(OAuthCallbackResult(error = exception.message))
            buildHtmlResponse("Authentication Failed", "Authentication failed.", false)
        }

        respond(HttpStatusCode.OK, responseText)
    }

    private fun stopServer() {
        server?.stop(0, 0)
        if (server != null) {
            LOG.info("OAuth callback server stopped")
        }
        server = null
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

        private fun buildAuthorizationUrl(config: OAuthClientConfig, challenge: String, state: String): String {
            val params = linkedMapOf(
                "client_id" to config.clientId,
                "redirect_uri" to config.redirectUri,
                "scope" to config.scopes,
                "code_challenge" to challenge,
                "code_challenge_method" to "S256",
                "response_type" to "code",
                "state" to state,
                "codex_cli_simplified_flow" to "true",
                "originator" to config.originator,
            )
            return "${config.authorizationEndpoint}?${OAuthUrlCodec.formEncode(params)}"
        }

        private fun generateCodeVerifier(): String {
            val random = ByteArray(32)
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
            val random = ByteArray(16)
            SecureRandom().nextBytes(random)
            return base64Url(random)
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
            """
            return response.trimIndent().format(title, background, title, message)
        }
    }
}
