package de.moritzf.proxy
import de.moritzf.proxy.auth.AuthFileResolver
import de.moritzf.proxy.auth.AuthManager
import de.moritzf.proxy.config.ServerConfig
import de.moritzf.proxy.model.ModelResolver
import de.moritzf.proxy.server.ApiKeyStore
import de.moritzf.proxy.server.ProxyServer
import de.moritzf.proxy.sse.SseParser
import de.moritzf.proxy.transport.CodexHttpClient
import de.moritzf.proxy.usage.UsageTracker
import de.moritzf.proxy.util.ApiKeyUtils
import de.moritzf.proxy.util.Json
import de.moritzf.proxy.util.ProxyVersion
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Locale
import java.util.concurrent.Callable
@Command(
    name = "AIProxyOauth",
    description = ["Local HTTP proxy server exposing OpenAI-compatible endpoints via ChatGPT OAuth tokens."],
    mixinStandardHelpOptions = true,
    versionProvider = AIProxyOauth.ManifestVersionProvider::class,
)
class AIProxyOauth : Callable<Int> {
    /** Reports the plugin version stamped into the jar manifest at build time. */
    class ManifestVersionProvider : CommandLine.IVersionProvider {
        override fun getVersion(): Array<String> = arrayOf("AIProxyOauth ${ProxyVersion.get()}")
    }
    @Option(names = ["--host"], description = ["Host interface to bind to. Default: 127.0.0.1"])
    private var host: String? = null
    @Option(names = ["--port"], description = ["Port to listen on. Default: 10531"])
    private var port: Int? = null
    @Option(names = ["--models"], description = ["Comma-separated model ids to expose from /v1/models."])
    private var models: String? = null
    @Option(names = ["--codex-version"], description = ["Codex API version to use for model discovery."])
    private var codexVersion: String? = null
    @Option(names = ["--base-url"], description = ["Override the upstream Codex base URL."])
    private var baseUrl: String? = null
    @Option(names = ["--oauth-client-id"], description = ["Override the OAuth client id used for refresh."])
    private var oauthClientId: String? = null
    @Option(names = ["--oauth-token-url"], description = ["Override the OAuth token URL used for refresh."])
    private var oauthTokenUrl: String? = null
    @Option(names = ["--oauth-file"], description = ["Path to the local auth.json file."])
    private var oauthFile: String? = null
    @Option(names = ["--store"], description = ["Whether to ask upstream to store responses. Default: false"])
    private var store = false
    @Option(names = ["--allow-any-cors"], description = ["Allow browser requests from any Origin. Default: false"])
    private var allowAnyCors = false
    @Option(
        names = ["--cors-origin"],
        split = ",",
        description = ["Browser Origin allowed by CORS. Can be repeated or comma-separated."],
    )
    private var corsOrigins: List<String>? = null
    @Option(
        names = ["--log-requests"],
        description = ["Log full proxied request/response metadata to disk with sensitive headers redacted. Default: false"],
    )
    private var logRequests = false
    @Option(
        names = ["--request-log-dir"],
        description = ["Directory for --log-requests output. Default: ./logs/requests"]
    )
    private var requestLogDir: String? = null
    @Option(
        names = ["--forward-prompt-cache-headers"],
        description = ["Forward prompt_cache_key as upstream conversation/session headers. Experimental. Default: false"],
    )
    private var forwardPromptCacheHeaders = false
    @Option(
        names = ["--responses-replay-cache"],
        description = ["Emulate previous_response_id/item_reference for store=false via an in-memory cache. Only needed for clients that chain responses server-side. Default: false"],
    )
    private var responsesReplayCache = false
    @Option(
        names = ["--codex-instructions"],
        description = ["Instruction source: configured or latest-codex. Default: configured"]
    )
    private var codexInstructionsMode: String? = null
    @Option(
        names = ["--codex-instructions-cache-dir"],
        description = ["Directory for cached latest Codex instructions. Default: ./cache/codex-instructions"],
    )
    private var codexInstructionsCacheDir: String? = null
    @Option(names = ["--api-key"], description = ["Comma-separated API keys clients must present."])
    private var apiKey: String? = null
    @Option(names = ["--api-keys-file"], description = ["Path to file with one API key per line."])
    private var apiKeysFile: String? = null
    @Option(
        names = ["--generate-key"],
        arity = "0..1",
        fallbackValue = "",
        description = ["Print a new random API key and exit. Optionally provide a name: --generate-key myapp"],
    )
    private var generateKey: String? = null
    @Option(names = ["--admin-key"], description = ["Owner key that can see all users' stats at GET /v1/usage."])
    private var adminKey: String? = null
    @CommandLine.Spec
    private lateinit var spec: CommandLine.Model.CommandSpec

    override fun call(): Int {
        if (generateKey != null) {
            return handleGenerateKey()
        }

        val config = buildServerConfig()
        if (config.fullRequestLogging) {
            System.err.println(
                "WARNING: full request logging is enabled. Request/response bodies may contain prompts, " +
                        "tool outputs, file paths, and other sensitive data. Authorization and API key headers are " +
                        "redacted, but logs should still be protected.",
            )
        }
        val inlineKeys = parseInlineKeys()
        if (config.adminKey != null) {
            inlineKeys.remove(config.adminKey)
        }
        val explicitAdminKey = adminKey?.takeIf { it.isNotBlank() }?.trim()
        val apiKeyStore = ApiKeyStore(inlineKeys, apiKeysFile, explicitAdminKey)
        if (!apiKeysFile.isNullOrBlank()) {
            apiKeyStore.reload()
        }
        apiKeyStore.startWatching()
        if (!checkAuthFileExists(config)) {
            return 1
        }
        val authHttpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
        val authManager = AuthManager(config, authHttpClient)
        // Initial auth load to verify credentials.
        val authResult = authManager.ensureFresh()
        val httpClient = CodexHttpClient(config, authManager)
        val modelResolver = ModelResolver(httpClient, config.models, config.codexVersion)
        // Discover models upfront.
        val availableModels = resolveAvailableModels(modelResolver)
        // Start server.
        val usageTracker = UsageTracker()
        val server = ProxyServer(config, httpClient, modelResolver, usageTracker, apiKeyStore)
        server.start()
        val startupProbe = HttpClient.newHttpClient().use { startupProbeClient ->
            verifyChatCompletionThroughProxy(
                config,
                availableModels,
                if (apiKeyStore.isEnforcing()) firstConfiguredApiKey(config) else null,
                startupProbeClient,
            )
        }
        printStartupBanner(config, availableModels, authResult.sourcePath, apiKeyStore.isEnforcing(), startupProbe)
        setupShutdownHook(server, authHttpClient, apiKeyStore)
        // Keep main thread alive.
        Thread.currentThread().join()
        return 0
    }
    private fun handleGenerateKey(): Int {
        val key = ApiKeyUtils.generateNewKey()
        spec.commandLine().out.println(if (generateKey.isNullOrEmpty()) key else "$generateKey:$key")
        return 0
    }

    private fun buildServerConfig(): ServerConfig {
        val apiKeyMap = parseApiKeyMap()
        var resolvedAdminKey = adminKey?.takeIf { it.isNotBlank() }?.trim()

        // If no CLI admin key, look for an entry named "admin" in the keys map.
        if (resolvedAdminKey == null) {
            var foundKey: String? = null
            for ((key, value) in apiKeyMap) {
                if (value.equals("admin", ignoreCase = true)) {
                    foundKey = key
                    break
                }
            }
            if (foundKey != null) {
                resolvedAdminKey = foundKey
                apiKeyMap.remove(foundKey) // Remove from regular keys.
            }
        }
        return ServerConfig(
            host ?: ServerConfig.DEFAULT_HOST,
            port ?: ServerConfig.DEFAULT_PORT,
            parseModelList(),
            codexVersion,
            baseUrl ?: ServerConfig.DEFAULT_BASE_URL,
            oauthClientId,
            oauthTokenUrl,
            oauthFile,
            ServerConfig.DEFAULT_INSTRUCTIONS,
            store,
            apiKeyMap,
            resolvedAdminKey,
            allowAnyCors,
            corsOrigins,
            logRequests,
            requestLogDir,
            forwardPromptCacheHeaders,
            codexInstructionsMode,
            codexInstructionsCacheDir,
            responsesReplayCache,
            true,
        )
    }
    private fun parseModelList(): List<String>? {
        if (models.isNullOrEmpty()) {
            return null
        }
        val modelList = models.orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return modelList.ifEmpty { null }
    }
    /** Returns only the keys from --api-key (not --api-keys-file). */
    private fun parseInlineKeys(): MutableMap<String, String> {
        val map = HashMap<String, String>()
        if (!apiKey.isNullOrBlank()) {
            apiKey.orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { ApiKeyUtils.parseKeyEntry(it, map) }
        }
        return map
    }

    private fun parseApiKeyMap(): MutableMap<String, String> {
        val apiKeyMap = HashMap<String, String>()
        if (!apiKey.isNullOrEmpty()) {
            apiKey.orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { ApiKeyUtils.parseKeyEntry(it, apiKeyMap) }
        }
        val apiKeysPath = apiKeysFile
        if (!apiKeysPath.isNullOrEmpty()) {
            Files.readAllLines(Path.of(apiKeysPath)).asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .forEach { ApiKeyUtils.parseKeyEntry(it, apiKeyMap) }
        }
        return apiKeyMap
    }

    private fun checkAuthFileExists(config: ServerConfig): Boolean {
        val existingAuthFile = findExistingAuthFile(config.oauthFilePath)
        if (existingAuthFile == null) {
            val candidates = AuthFileResolver.resolveCandidates(config.oauthFilePath)
            if (!config.oauthFilePath.isNullOrEmpty()) {
                System.err.println("No auth file was found at ${config.oauthFilePath}.")
            } else {
                System.err.println("No auth file was found in the default search paths: ${candidates.joinToString(", ")}.")
            }
            System.err.println("Run `codex login` and try again.")
            return false
        }
        return true
    }
    private fun resolveAvailableModels(modelResolver: ModelResolver): List<String> {
        return try {
            modelResolver.resolveModels()
        } catch (exception: Exception) {
            System.err.println("Warning: Could not discover models: $exception")
            emptyList()
        }
    }
    private fun printStartupBanner(
        config: ServerConfig,
        availableModels: List<String>,
        authFilePath: String?,
        apiKeyEnforcement: Boolean,
        startupProbe: StartupProbeResult?,
    ) {
        val out = spec.commandLine().out
        val url = "http://${config.host}:${config.port}/v1"
        out.println()
        out.println("OpenAI OAuth Proxy Server started")
        out.println("  Endpoint: $url")
        if (availableModels.isNotEmpty()) {
            out.println("  Models:   ${availableModels.joinToString(", ")}")
        }
        out.println("  Client API key enforcement: ${if (apiKeyEnforcement) "enabled" else "disabled"}")
        out.println("  Network access: ${describeNetworkAccess(config.host)}")
        out.println("  CORS: ${describeCors(config)}")
        if (!authFilePath.isNullOrBlank()) {
            out.println("  Auth file: $authFilePath")
        }
        if (startupProbe != null) {
            val checkStatus = if (startupProbe.success) {
                "chat completion OK"
            } else {
                "chat completion failed (${startupProbe.message})"
            }
            out.println("  Startup check: $checkStatus (model: ${startupProbe.model})")
            if (!startupProbe.responseText.isNullOrBlank()) {
                out.println("  Startup response: ${startupProbe.responseText}")
            }
        }
        if (config.apiKeys.isNotEmpty()) {
            val names = config.apiKeys.values.joinToString(", ")
            out.println("  Keys:     ${config.apiKeys.size} key(s) configured ($names)")
        }
        if (config.adminKey != null) {
            out.println("  Admin:    key configured")
        }
        out.println()
    }
    private data class StartupProbeResult(
        val success: Boolean,
        val statusCode: Int,
        val message: String,
        val responseText: String?,
        val model: String,
    )
    private fun verifyChatCompletionThroughProxy(
        config: ServerConfig,
        availableModels: List<String>,
        apiKey: String?,
        httpClient: HttpClient,
    ): StartupProbeResult {
        val model = selectStartupProbeModel(config, availableModels)
        val body = """{"model":"$model","messages":[{"role":"user","content":"Hello!"}],"stream":true}"""
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(startupProbeUrl(config)))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        if (!apiKey.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }
        return try {
            val response = httpClient.send(
                requestBuilder.build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            val status = response.statusCode()
            val responseText = if (status in 200..<300) {
                extractStartupProbeResponseText(response.body())
            } else {
                formatStartupProbeRawBody(response.body())
            }
            if (status in 200..<300) {
                val hasModelResponse = hasActualStartupProbeResponse(responseText)
                return StartupProbeResult(
                    hasModelResponse,
                    status,
                    if (hasModelResponse) "HTTP $status" else "HTTP $status, no model response text",
                    responseText,
                    model,
                )
            }
            StartupProbeResult(false, status, "HTTP $status", responseText, model)
        } catch (exception: Exception) {
            StartupProbeResult(false, 0, "${exception.javaClass.simpleName}: ${exception.message}", null, model)
        }
    }
    private fun setupShutdownHook(server: ProxyServer, authHttpClient: HttpClient, apiKeyStore: ApiKeyStore) {
        Runtime.getRuntime().addShutdownHook(
            Thread(
                {
                    println("Shutting down...")
                    server.stop()
                    authHttpClient.close()
                    apiKeyStore.stopWatching()
                },
                "shutdown-hook",
            ),
        )
    }
    companion object {
        private fun extractStartupProbeResponseText(responseBody: String?): String {
            if (responseBody.isNullOrBlank()) {
                return "<empty response body>"
            }
            if (looksLikeSse(responseBody)) {
                return extractStreamingStartupProbeResponseText(responseBody)
            }
            return try {
                val root = Json.MAPPER.readTree(responseBody)
                val choices = root.get("choices")
                if (choices == null || !choices.isArray || choices.isEmpty) {
                    return "<missing choices[0].message.content>"
                }
                val firstChoice = choices.get(0)
                val message = firstChoice?.get("message")
                val content = message?.get("content") ?: return "<missing choices[0].message.content>"
                if (content.isNull) {
                    return "<null choices[0].message.content>"
                }
                if (content.isTextual) {
                    return formatStartupProbeText(content.asText())
                }
                formatStartupProbeText(Json.MAPPER.writeValueAsString(content))
            } catch (_: Exception) {
                "<unparseable response body: ${formatStartupProbeText(responseBody)}>"
            }
        }
        private fun extractStreamingStartupProbeResponseText(responseBody: String): String {
            val text = StringBuilder()
            var sawNullContent = false
            try {
                val input = ByteArrayInputStream(responseBody.toByteArray(StandardCharsets.UTF_8))
                for (event in SseParser.parse(input)) {
                    val data = event.data()
                    if (data.isNullOrBlank()) {
                        continue
                    }
                    if (data == "[DONE]") {
                        break
                    }
                    val root = Json.MAPPER.readTree(data)
                    val choices = root.get("choices")
                    if (choices == null || !choices.isArray) {
                        continue
                    }
                    for (choice in choices) {
                        val delta = choice.get("delta")
                        val content = delta?.get("content") ?: continue
                        if (content.isNull) {
                            sawNullContent = true
                        } else if (content.isTextual) {
                            text.append(content.asText())
                        } else {
                            text.append(Json.MAPPER.writeValueAsString(content))
                        }
                    }
                }
            } catch (_: Exception) {
                return "<unparseable streaming response body: ${formatStartupProbeText(responseBody)}>"
            }
            if (text.isNotEmpty()) {
                return formatStartupProbeText(text.toString())
            }
            return if (sawNullContent) {
                "<null streaming choices[].delta.content>"
            } else {
                "<missing streaming choices[].delta.content>"
            }
        }
        private fun looksLikeSse(responseBody: String): Boolean {
            val trimmed = responseBody.trimStart()
            return trimmed.startsWith("data:") || trimmed.startsWith("event:")
        }
        private fun hasActualStartupProbeResponse(responseText: String?): Boolean {
            return !responseText.isNullOrBlank() && !responseText.startsWith("<")
        }
        private fun formatStartupProbeRawBody(responseBody: String?): String {
            if (responseBody.isNullOrBlank()) {
                return "<empty response body>"
            }
            return formatStartupProbeText(responseBody)
        }
        private fun formatStartupProbeText(text: String): String {
            return text.replace("\r", "\\r").replace("\n", "\\n")
        }
        private fun selectStartupProbeModel(config: ServerConfig, availableModels: List<String>): String {
            if (availableModels.isNotEmpty()) {
                return availableModels.first()
            }
            val configuredModels = config.models
            if (!configuredModels.isNullOrEmpty()) {
                return configuredModels.first()
            }
            return ServerConfig.DEFAULT_MODEL
        }
        private fun startupProbeUrl(config: ServerConfig): String {
            val host = clientHostForBindHost(config.host)
            return "http://${hostForUri(host)}:${config.port}/v1/chat/completions"
        }
        private fun clientHostForBindHost(host: String?): String {
            if (host.isNullOrBlank()) {
                return ServerConfig.DEFAULT_HOST
            }
            val normalized = host.trim().lowercase(Locale.ROOT)
            if (normalized == "0.0.0.0" || normalized == "::" || normalized == "0:0:0:0:0:0:0:0") {
                return ServerConfig.DEFAULT_HOST
            }
            return host.trim()
        }
        private fun hostForUri(host: String): String {
            return if (host.contains(":") && !host.startsWith("[")) "[$host]" else host
        }
        private fun firstConfiguredApiKey(config: ServerConfig): String? {
            if (config.adminKey != null) {
                return config.adminKey
            }
            return config.apiKeys.keys.firstOrNull()
        }
        internal fun describeNetworkAccess(host: String?): String {
            return if (isLocalOnlyHost(host)) "Local access only" else "Full network access"
        }
        internal fun describeCors(config: ServerConfig): String {
            if (config.allowAnyCors) {
                return "any origin"
            }
            if (config.allowedCorsOrigins.isNotEmpty()) {
                return config.allowedCorsOrigins.joinToString(", ")
            }
            return "disabled"
        }
        private fun isLocalOnlyHost(host: String?): Boolean {
            if (host.isNullOrBlank()) {
                return true
            }
            val normalized = host.trim().lowercase(Locale.ROOT)
            return normalized == "localhost" ||
                    normalized == "::1" ||
                    normalized == "0:0:0:0:0:0:0:1" ||
                    normalized.startsWith("127.")
        }
        internal fun findExistingAuthFile(authFilePath: String?): String? {
            for (candidate in AuthFileResolver.resolveCandidates(authFilePath)) {
                if (Files.exists(Path.of(candidate))) {
                    return candidate
                }
            }
            return null
        }
        @JvmStatic
        fun main(args: Array<String>) {
            val exitCode = CommandLine(AIProxyOauth()).execute(*args)
            kotlin.system.exitProcess(exitCode)
        }
    }
}
