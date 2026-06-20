package de.moritzf.quota.openai.proxy

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import de.moritzf.quota.openai.dto.OAuthTokenResponseDto
import de.moritzf.quota.shared.JsonSupport
import java.awt.Desktop
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

fun main(args: Array<String>) {
    val options = parseStandaloneOptions(args)
    val credentials = if (options.login) {
        loginCredentials().also(::saveCredentialsToDotEnv)
    } else {
        loadCredentials()
    }
    val credentialManager = StandaloneCredentialManager(credentials)
    val accessToken = credentialManager.accessToken() ?: error(
        "OPENAI_PROXY_ACCESS_TOKEN or OPENAI_PROXY_REFRESH_TOKEN, .env, OPENAI_PROXY_CREDENTIALS_FILE, or --login is required",
    )
    val localApiKey = env("OPENAI_PROXY_API_KEY") ?: DEFAULT_LOCAL_API_KEY
    val port = env("OPENAI_PROXY_PORT")?.toIntOrNull() ?: DEFAULT_PORT

    val proxy = OpenAiProxyServer(
        port = port,
        localApiKeyProvider = { localApiKey },
        accessTokenProvider = { credentialManager.accessToken() },
        accountIdProvider = { credentialManager.accountId() },
        tokenRefresher = credentialManager::refreshAfterRejected,
        debugLogger = debugLogger(),
        fullRequestLogging = env("OPENAI_PROXY_LOG_REQUESTS").toBooleanFlag(),
        requestLogDir = env("OPENAI_PROXY_REQUEST_LOG_DIR") ?: DEFAULT_REQUEST_LOG_DIR,
        allowAnyCors = options.allowAnyCors || env("OPENAI_PROXY_ALLOW_ANY_CORS").toBooleanFlag(),
        allowedCorsOrigins = options.corsOrigins + parseCommaSeparatedList(env("OPENAI_PROXY_CORS_ORIGINS")),
        consoleAccessLog = true,
    )
    Runtime.getRuntime().addShutdownHook(Thread { proxy.stop() })
    proxy.start()
    println("OpenAI standalone proxy listening at http://127.0.0.1:$port")
    println("Use OPENAI_PROXY_API_KEY as the local bearer token.")
    CountDownLatch(1).await()
}

internal data class StandaloneProxyOptions(
    val login: Boolean = false,
    val allowAnyCors: Boolean = false,
    val corsOrigins: List<String> = emptyList(),
)

internal fun parseStandaloneOptions(args: Array<String>): StandaloneProxyOptions {
    var login = false
    var allowAnyCors = false
    val corsOrigins = mutableListOf<String>()
    var index = 0
    while (index < args.size) {
        val arg = args[index]
        when {
            arg == "--login" -> login = true
            arg == "--allow-any-cors" -> allowAnyCors = true
            arg == "--cors-origin" || arg == "--cors-url" -> {
                index += 1
                require(index < args.size) { "$arg requires a value" }
                corsOrigins += parseCommaSeparatedList(args[index])
            }
            arg.startsWith("--cors-origin=") -> corsOrigins += parseCommaSeparatedList(arg.substringAfter('='))
            arg.startsWith("--cors-url=") -> corsOrigins += parseCommaSeparatedList(arg.substringAfter('='))
            else -> error("Unknown argument: $arg")
        }
        index += 1
    }
    return StandaloneProxyOptions(login, allowAnyCors, corsOrigins)
}

private fun parseCommaSeparatedList(value: String?): List<String> {
    return value
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()
}

@Volatile
private var loadedDotEnv: Map<String, String>? = null

private fun env(name: String): String? {
    return System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: dotEnv()[name]?.takeIf { it.isNotBlank() }
}

private fun dotEnv(): Map<String, String> {
    val cached = loadedDotEnv
    if (cached != null) return cached
    val loaded = loadDotEnv(DOT_ENV_PATH)
    loadedDotEnv = loaded
    return loaded
}

private fun debugLogger(): ((String) -> Unit)? {
    val path = env("OPENAI_PROXY_DEBUG_LOG") ?: return null
    val file = Path.of(path)
    Files.createDirectories(file.parent ?: Path.of("."))
    return { message ->
        val entry = "\n--- ${java.time.Instant.now()} ---\n$message\n"
        synchronized(file.toAbsolutePath().toString().intern()) {
            Files.writeString(
                file,
                entry,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND,
            )
        }
    }
}

private fun loginCredentials(): StandaloneCredentials {
    val verifier = randomBase64Url(32)
    val challenge = sha256Base64Url(verifier)
    val state = randomBase64Url(16)
    val callback = AtomicReference<OAuthCallback?>()
    val latch = CountDownLatch(1)
    val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), OAUTH_CALLBACK_PORT), 0)
    server.createContext("/auth/callback") { exchange ->
        handleOAuthCallback(exchange, state, callback, latch)
    }
    server.createContext("/auth/ping") { exchange ->
        sendText(exchange, 200, "OK", "text/plain; charset=utf-8")
    }
    server.start()
    try {
        val authorizationUrl = buildAuthorizationUrl(challenge, state)
        println("Opening OpenAI login in your browser...")
        openBrowser(authorizationUrl)
        println("If the browser did not open, visit this URL:")
        println(authorizationUrl)
        check(latch.await(10, TimeUnit.MINUTES)) { "Authentication timed out" }
        val result = callback.get() ?: error("OAuth callback did not complete")
        result.error?.let { error(it) }
        val code = result.code ?: error("OAuth callback did not include an authorization code")
        return exchangeAuthorizationCode(code, verifier)
    } finally {
        server.stop(0)
    }
}

private fun openBrowser(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(URI.create(url))
        }
    }
}

private fun handleOAuthCallback(
    exchange: HttpExchange,
    expectedState: String,
    callback: AtomicReference<OAuthCallback?>,
    latch: CountDownLatch,
) {
    try {
        if (exchange.requestMethod != "GET") {
            sendText(exchange, 405, "Method Not Allowed", "text/plain; charset=utf-8")
            return
        }
        val remoteAddress = exchange.remoteAddress?.address
        if (remoteAddress == null || !remoteAddress.isLoopbackAddress) {
            sendText(exchange, 403, "Forbidden", "text/plain; charset=utf-8")
            return
        }
        val params = parseQuery(exchange.requestURI.rawQuery)
        val error = params["error"]
        val code = params["code"]
        val state = params["state"]
        val result = when {
            error != null -> OAuthCallback(error = "OAuth error: $error")
            code.isNullOrBlank() || state.isNullOrBlank() -> OAuthCallback(error = "Missing code or state")
            state != expectedState -> OAuthCallback(error = "State mismatch")
            else -> OAuthCallback(code = code)
        }
        callback.set(result)
        latch.countDown()
        val ok = result.error == null
        sendText(
            exchange,
            200,
            "<html><body><h1>${if (ok) "Authentication Successful" else "Authentication Failed"}</h1>" +
                "<p>${if (ok) "You can close this window." else result.error}</p></body></html>",
            "text/html; charset=utf-8",
        )
    } catch (exception: Exception) {
        callback.set(OAuthCallback(error = exception.message ?: exception::class.java.simpleName))
        latch.countDown()
        sendText(exchange, 500, "Authentication failed", "text/plain; charset=utf-8")
    }
}

private fun exchangeAuthorizationCode(code: String, verifier: String): StandaloneCredentials {
    val body = formEncode(
        "grant_type" to "authorization_code",
        "client_id" to OAUTH_CLIENT_ID,
        "code" to code,
        "redirect_uri" to OAUTH_REDIRECT_URI,
        "code_verifier" to verifier,
    )
    val request = HttpRequest.newBuilder(URI.create(OAUTH_TOKEN_ENDPOINT))
        .timeout(Duration.ofSeconds(30))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("Accept", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    check(response.statusCode() in 200..299) { "Token exchange failed: HTTP ${response.statusCode()} ${response.body()}" }
    return credentialsFromTokenResponse(response.body())
}

private fun refreshCredentials(refreshToken: String, fallback: StandaloneCredentials): StandaloneCredentials {
    val body = formEncode(
        "grant_type" to "refresh_token",
        "client_id" to OAUTH_CLIENT_ID,
        "refresh_token" to refreshToken,
    )
    val request = HttpRequest.newBuilder(URI.create(OAUTH_TOKEN_ENDPOINT))
        .timeout(Duration.ofSeconds(30))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("Accept", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    check(response.statusCode() in 200..299) { "Token refresh failed: HTTP ${response.statusCode()} ${response.body()}" }
    val refreshed = credentialsFromTokenResponse(response.body())
    return refreshed.copy(
        refreshToken = refreshed.refreshToken ?: fallback.refreshToken,
        accountId = refreshed.accountId ?: fallback.accountId,
    )
}

internal fun credentialsFromTokenResponse(body: String): StandaloneCredentials {
    val response = JsonSupport.json.decodeFromString<OAuthTokenResponseDto>(body)
    val accessToken = response.accessToken?.takeIf { it.isNotBlank() }
        ?: error("Token response did not include access_token")
    val expiresAt = resolveExpiresAt(accessToken, response.expiresIn)
    return StandaloneCredentials(
        accessToken = accessToken,
        refreshToken = response.refreshToken?.takeIf { it.isNotBlank() },
        expiresAt = expiresAt,
        accountId = extractChatGptAccountId(response.idToken) ?: extractChatGptAccountId(accessToken),
    )
}

private fun resolveExpiresAt(accessToken: String, expiresInSeconds: Long): Long {
    if (expiresInSeconds > 0) {
        return System.currentTimeMillis() + expiresInSeconds * 1000L
    }
    return extractJwtExpiresAtMs(accessToken) ?: 0L
}

private fun buildAuthorizationUrl(challenge: String, state: String): String {
    val query = formEncode(
        "client_id" to OAUTH_CLIENT_ID,
        "redirect_uri" to OAUTH_REDIRECT_URI,
        "scope" to "openid profile email offline_access",
        "code_challenge" to challenge,
        "code_challenge_method" to "S256",
        "response_type" to "code",
        "state" to state,
        "codex_cli_simplified_flow" to "true",
        "originator" to "openai-usage-quota-plugin",
    )
    return "$OAUTH_AUTHORIZATION_ENDPOINT?$query"
}

private fun parseQuery(query: String?): Map<String, String> {
    if (query.isNullOrBlank()) return emptyMap()
    return query.split('&').mapNotNull { pair ->
        val index = pair.indexOf('=')
        if (index <= 0) return@mapNotNull null
        URLDecoder.decode(pair.substring(0, index), Charsets.UTF_8) to
            URLDecoder.decode(pair.substring(index + 1), Charsets.UTF_8)
    }.toMap()
}

private fun formEncode(vararg params: Pair<String, String>): String {
    return params.joinToString("&") { (key, value) ->
        "${URLEncoder.encode(key, Charsets.UTF_8)}=${URLEncoder.encode(value, Charsets.UTF_8)}"
    }
}

private fun sendText(exchange: HttpExchange, status: Int, body: String, contentType: String) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    exchange.responseHeaders.set("Content-Type", contentType)
    exchange.sendResponseHeaders(status, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
    exchange.close()
}

private fun randomBase64Url(size: Int): String {
    val bytes = ByteArray(size)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private fun sha256Base64Url(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

private fun extractChatGptAccountId(jwt: String?): String? {
    val payload = jwt?.split('.')?.getOrNull(1) ?: return null
    val json = runCatching { String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8) }.getOrNull() ?: return null
    val root = runCatching { JsonSupport.json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
    return root["https://api.openai.com/auth"]?.jsonObject
        ?.get("chatgpt_account_id")?.jsonPrimitive?.content
        ?: root["email"]?.jsonPrimitive?.content
}

private fun extractJwtExpiresAtMs(jwt: String): Long? {
    val payload = jwt.split('.').getOrNull(1) ?: return null
    val json = runCatching { String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8) }.getOrNull() ?: return null
    val root = runCatching { JsonSupport.json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
    return root["exp"]?.jsonPrimitive?.longOrNull?.times(1000L)
}

private fun loadCredentials(): StandaloneCredentials {
    val fileCredentials = env("OPENAI_PROXY_CREDENTIALS_FILE")?.let(::credentialsFromFile)
    return StandaloneCredentials(
        accessToken = env("OPENAI_PROXY_ACCESS_TOKEN") ?: fileCredentials?.accessToken,
        refreshToken = env("OPENAI_PROXY_REFRESH_TOKEN") ?: fileCredentials?.refreshToken,
        expiresAt = env("OPENAI_PROXY_EXPIRES_AT")?.toLongOrNull() ?: fileCredentials?.expiresAt ?: 0L,
        accountId = env("OPENAI_PROXY_ACCOUNT_ID") ?: fileCredentials?.accountId,
    )
}

private fun loadDotEnv(path: Path): Map<String, String> {
    if (!Files.exists(path)) return emptyMap()
    return Files.readAllLines(path).mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith('#')) return@mapNotNull null
        val index = trimmed.indexOf('=')
        if (index <= 0) return@mapNotNull null
        val key = trimmed.substring(0, index).trim()
        val value = trimmed.substring(index + 1).trim().unquoteDotEnvValue()
        key.takeIf { it.isNotBlank() }?.let { it to value }
    }.toMap()
}

private fun String.unquoteDotEnvValue(): String {
    if (length >= 2 && ((first() == '"' && last() == '"') || (first() == '\'' && last() == '\''))) {
        return substring(1, length - 1)
    }
    return this
}

private fun saveCredentialsToDotEnv(credentials: StandaloneCredentials) {
    val accessToken = credentials.accessToken?.takeIf { it.isNotBlank() } ?: return
    val existing = loadDotEnv(DOT_ENV_PATH).toMutableMap()
    existing["OPENAI_PROXY_ACCESS_TOKEN"] = accessToken
    credentials.refreshToken?.takeIf { it.isNotBlank() }?.let { existing["OPENAI_PROXY_REFRESH_TOKEN"] = it }
    if (credentials.expiresAt > 0) {
        existing["OPENAI_PROXY_EXPIRES_AT"] = credentials.expiresAt.toString()
    }
    credentials.accountId?.takeIf { it.isNotBlank() }?.let { existing["OPENAI_PROXY_ACCOUNT_ID"] = it }
    existing.putIfAbsent("OPENAI_PROXY_API_KEY", DEFAULT_LOCAL_API_KEY)
    existing.putIfAbsent("OPENAI_PROXY_PORT", DEFAULT_PORT.toString())
    val content = existing.entries.joinToString("\n", postfix = "\n") { (key, value) ->
        "$key=${value.toDotEnvValue()}"
    }
    // The file holds the OAuth access token; restrict it to the owner before writing,
    // mirroring AuthLoader's handling of auth.json.
    restrictToOwner(DOT_ENV_PATH)
    Files.writeString(DOT_ENV_PATH, content)
    loadedDotEnv = existing.toMap()
    println("Saved standalone proxy credentials to ${DOT_ENV_PATH.toAbsolutePath().normalize()}")
}

private fun restrictToOwner(path: Path) {
    runCatching {
        if (!Files.exists(path)) {
            Files.createFile(path)
        }
        if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(
                path,
                setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                ),
            )
        }
    }
}

private fun String.toDotEnvValue(): String {
    return if (any { it.isWhitespace() || it == '#' || it == '"' || it == '\'' }) {
        "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""
    } else {
        this
    }
}

private fun String?.toBooleanFlag(): Boolean {
    return this != null && lowercase() in setOf("1", "true", "yes", "on")
}

private fun credentialsFromFile(filePath: String): StandaloneCredentials {
    val root = JsonSupport.json.parseToJsonElement(Files.readString(Path.of(filePath))).jsonObject
    return StandaloneCredentials(
        accessToken = root["accessToken"]?.jsonPrimitive?.content
            ?: root["access_token"]?.jsonPrimitive?.content,
        refreshToken = root["refreshToken"]?.jsonPrimitive?.contentOrNull
            ?: root["refresh_token"]?.jsonPrimitive?.contentOrNull,
        expiresAt = root["expiresAt"]?.jsonPrimitive?.longOrNull
            ?: root["expires_at"]?.jsonPrimitive?.longOrNull
            ?: 0L,
        accountId = root["accountId"]?.jsonPrimitive?.content
            ?: root["account_id"]?.jsonPrimitive?.content,
    )
}

private class StandaloneCredentialManager(initialCredentials: StandaloneCredentials) {
    private var credentials = initialCredentials

    @Synchronized
    fun accessToken(): String? {
        if (credentials.accessToken.isNullOrBlank() || credentials.isExpired()) {
            refreshLocked()?.let { return it.accessToken }
        }
        return credentials.accessToken
    }

    @Synchronized
    fun accountId(): String? = credentials.accountId

    @Synchronized
    fun refreshAfterRejected(staleAccessToken: String?): String? {
        if (!staleAccessToken.isNullOrBlank() && credentials.accessToken != staleAccessToken) {
            return credentials.accessToken
        }
        return refreshLocked()?.accessToken
    }

    private fun refreshLocked(): StandaloneCredentials? {
        val refreshToken = credentials.refreshToken?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { refreshCredentials(refreshToken, credentials) }
            .onSuccess { refreshed ->
                credentials = refreshed
                saveCredentialsToDotEnv(refreshed)
            }
            .getOrNull()
    }
}

internal data class StandaloneCredentials(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresAt: Long = 0L,
    val accountId: String? = null,
) {
    fun isExpired(): Boolean = expiresAt > 0 && System.currentTimeMillis() >= expiresAt - TOKEN_EXPIRY_SKEW_MS
}

private data class OAuthCallback(
    val code: String? = null,
    val error: String? = null,
)

private const val OAUTH_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
private const val OAUTH_AUTHORIZATION_ENDPOINT = "https://auth.openai.com/oauth/authorize"
private const val OAUTH_TOKEN_ENDPOINT = "https://auth.openai.com/oauth/token"
private const val OAUTH_REDIRECT_URI = "http://localhost:1455/auth/callback"
private const val OAUTH_CALLBACK_PORT = 1455
private const val DEFAULT_LOCAL_API_KEY = "sk-quota-standalone-local"
private const val DEFAULT_PORT = 14622
private const val DEFAULT_REQUEST_LOG_DIR = "logs/openai-proxy-requests"
private const val TOKEN_EXPIRY_SKEW_MS = 5 * 60 * 1000L
private val DOT_ENV_PATH: Path = Path.of(".env")
