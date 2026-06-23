package de.moritzf.proxy.subscription

import de.moritzf.quota.github.GitHubDeviceTokenPollResult
import de.moritzf.quota.github.GitHubOAuthClient
import de.moritzf.quota.github.proxy.GitHubCopilotSubscriptionProxyProvider
import de.moritzf.quota.kimi.KimiCredentials
import de.moritzf.quota.kimi.proxy.KimiSubscriptionProxyProvider
import de.moritzf.quota.minimax.MiniMaxRegion
import de.moritzf.quota.minimax.proxy.MiniMaxSubscriptionProxyProvider
import de.moritzf.quota.ollama.proxy.OllamaSubscriptionProxyProvider
import de.moritzf.quota.openai.proxy.OpenAiCodexSubscriptionProxyProvider
import de.moritzf.quota.opencode.proxy.OpenCodeZenSubscriptionProxyProvider
import de.moritzf.quota.supergrok.proxy.SuperGrokSubscriptionProxyProvider
import de.moritzf.quota.zai.proxy.ZaiSubscriptionProxyProvider
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

fun main(args: Array<String>) {
    val options = parseStandaloneSubscriptionOptions(args)
    if (options.login) {
        val token = loginGitHubCopilot()
        saveGitHubCredentialsToDotEnv(options.envFile, token, envLocalApiKey = options.localApiKey)
    }
    val env = StandaloneEnv.load(options.envFile)
    val localApiKey = options.localApiKey ?: env.value("SUBSCRIPTION_PROXY_API_KEY") ?: DEFAULT_LOCAL_API_KEY
    val providers = createProviders(options.providers, env, options)
    val enabledProviders = providers.filter { it.isConfigured() }
    if (options.listModels) {
        printModels(enabledProviders)
        return
    }
    require(enabledProviders.isNotEmpty()) {
        "No configured providers selected. Add tokens to ${options.envFile} or environment variables."
    }
    val proxy = SubscriptionProxyServer(
        port = options.port,
        localApiKeyProvider = { localApiKey },
        providers = { enabledProviders },
        host = options.host,
        allowAnyCors = options.allowAnyCors,
        allowedCorsOrigins = options.corsOrigins,
        fullRequestLogging = options.logRequests,
        requestLogDir = options.requestLogDir,
    )
    Runtime.getRuntime().addShutdownHook(Thread { proxy.stop() })
    proxy.start()
    println("Subscription proxy listening at http://${options.host}:${options.port}")
    println("Use SUBSCRIPTION_PROXY_API_KEY as the local bearer token.")
    println("Enabled providers: ${enabledProviders.joinToString { it.id }}")
    CountDownLatch(1).await()
}

internal data class StandaloneSubscriptionOptions(
    val host: String = "127.0.0.1",
    val port: Int = DEFAULT_PORT,
    val envFile: Path = DEFAULT_ENV_FILE,
    val providers: Set<String> = setOf("openai", "supergrok", "github", "kimi", "minimax", "ollama", "opencode", "zai"),
    val allowAnyCors: Boolean = false,
    val corsOrigins: List<String> = emptyList(),
    val logRequests: Boolean = false,
    val requestLogDir: String = DEFAULT_REQUEST_LOG_DIR,
    val listModels: Boolean = false,
    val login: Boolean = false,
    val localApiKey: String? = null,
)

internal fun parseStandaloneSubscriptionOptions(args: Array<String>): StandaloneSubscriptionOptions {
    var options = StandaloneSubscriptionOptions()
    var index = 0
    while (index < args.size) {
        val arg = args[index]
        fun requireValue(): String {
            index += 1
            require(index < args.size) { "$arg requires a value" }
            return args[index]
        }
        when {
            arg == "--host" -> options = options.copy(host = requireValue())
            arg.startsWith("--host=") -> options = options.copy(host = arg.substringAfter('='))
            arg == "--port" -> options = options.copy(port = requireValue().toInt())
            arg.startsWith("--port=") -> options = options.copy(port = arg.substringAfter('=').toInt())
            arg == "--env-file" -> options = options.copy(envFile = Path.of(requireValue()))
            arg.startsWith("--env-file=") -> options = options.copy(envFile = Path.of(arg.substringAfter('=')))
            arg == "--provider" -> options = options.copy(providers = parseProviders(requireValue()))
            arg.startsWith("--provider=") -> options = options.copy(providers = parseProviders(arg.substringAfter('=')))
            arg == "--allow-any-cors" -> options = options.copy(allowAnyCors = true)
            arg == "--cors-origin" || arg == "--cors-url" -> {
                options = options.copy(corsOrigins = options.corsOrigins + parseCommaSeparatedList(requireValue()))
            }
            arg.startsWith("--cors-origin=") -> {
                options = options.copy(corsOrigins = options.corsOrigins + parseCommaSeparatedList(arg.substringAfter('=')))
            }
            arg.startsWith("--cors-url=") -> {
                options = options.copy(corsOrigins = options.corsOrigins + parseCommaSeparatedList(arg.substringAfter('=')))
            }
            arg == "--log-requests" -> options = options.copy(logRequests = true)
            arg == "--request-log-dir" -> options = options.copy(requestLogDir = requireValue())
            arg.startsWith("--request-log-dir=") -> options = options.copy(requestLogDir = arg.substringAfter('='))
            arg == "--list-models" -> options = options.copy(listModels = true)
            arg == "--login" -> options = options.copy(login = true)
            arg == "--local-api-key" -> options = options.copy(localApiKey = requireValue())
            arg.startsWith("--local-api-key=") -> options = options.copy(localApiKey = arg.substringAfter('='))
            else -> error("Unknown argument: $arg")
        }
        index += 1
    }
    require(options.port in 1..65535) { "Port must be in range 1-65535, got: ${options.port}" }
    return options
}

private fun loginGitHubCopilot(): String {
    val client = GitHubOAuthClient()
    val authorization = client.requestDeviceAuthorization()
    require(authorization.deviceCode.isNotBlank() && authorization.userCode.isNotBlank()) {
        "GitHub device authorization response was incomplete."
    }
    println("Opening GitHub device login in your browser...")
    openBrowser(authorization.verificationUri)
    copyToClipboard(authorization.userCode)
    println("If the browser did not open, visit: ${authorization.verificationUri}")
    println("Enter code: ${authorization.userCode} (copied to clipboard)")

    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(authorization.expiresInSeconds.toLong())
    var intervalSeconds = authorization.intervalSeconds.coerceAtLeast(1)
    while (System.nanoTime() < deadline) {
        Thread.sleep(TimeUnit.SECONDS.toMillis(intervalSeconds.toLong()))
        when (val result = client.pollDeviceToken(authorization.deviceCode)) {
            is GitHubDeviceTokenPollResult.Authorized -> {
                println("GitHub login successful.")
                return result.credentials.accessToken
            }

            is GitHubDeviceTokenPollResult.Pending -> {
                intervalSeconds = when {
                    result.nextIntervalSeconds > 0 -> result.nextIntervalSeconds
                    result.slowDown -> intervalSeconds + 5
                    else -> intervalSeconds
                }
            }
        }
    }
    error("GitHub login timed out. Run --login again.")
}

private fun copyToClipboard(value: String) {
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(value), null)
    }
}

private fun openBrowser(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(URI.create(url))
        }
    }
}

private fun createProviders(
    selected: Set<String>,
    env: StandaloneEnv,
    options: StandaloneSubscriptionOptions,
): List<SubscriptionProxyProvider> {
    val providers = mutableListOf<SubscriptionProxyProvider>()
    if ("openai" in selected) {
        providers += OpenAiCodexSubscriptionProxyProvider(
            accessTokenProvider = { env.value("OPENAI_PROXY_ACCESS_TOKEN") },
            accountIdProvider = { env.value("OPENAI_PROXY_ACCOUNT_ID") },
            fullRequestLogging = options.logRequests,
            requestLogDir = options.requestLogDir,
        )
    }
    if ("supergrok" in selected || "grok" in selected || "xai" in selected) {
        providers += SuperGrokSubscriptionProxyProvider(
            accessTokenProvider = {
                env.value("SUPERGROK_PROXY_ACCESS_TOKEN") ?: env.value("SUPERGROK_ACCESS_TOKEN")
            },
            fullRequestLogging = options.logRequests,
            requestLogDir = options.requestLogDir,
        )
    }
    if ("github" in selected || "copilot" in selected) {
        providers += GitHubCopilotSubscriptionProxyProvider(
            accessTokenProvider = { env.value("GITHUB_COPILOT_PROXY_ACCESS_TOKEN") },
            fullRequestLogging = options.logRequests,
            requestLogDir = options.requestLogDir,
        )
    }
    if ("kimi" in selected) {
        providers += KimiSubscriptionProxyProvider(
            credentialsProvider = {
                val accessToken = env.value("KIMI_PROXY_ACCESS_TOKEN") ?: env.value("KIMI_ACCESS_TOKEN")
                val refreshToken = env.value("KIMI_PROXY_REFRESH_TOKEN") ?: env.value("KIMI_REFRESH_TOKEN")
                if (accessToken.isNullOrBlank() && refreshToken.isNullOrBlank()) null else KimiCredentials(
                    accessToken = accessToken.orEmpty(),
                    refreshToken = refreshToken.orEmpty(),
                )
            },
            fullRequestLogging = options.logRequests,
            requestLogDir = options.requestLogDir,
        )
    }
    if ("minimax" in selected) {
        providers += MiniMaxSubscriptionProxyProvider(
            apiKeyProvider = { env.value("MINIMAX_PROXY_API_KEY") ?: env.value("MINIMAX_API_KEY") },
            regionProvider = { miniMaxRegion(env.value("MINIMAX_PROXY_REGION") ?: env.value("MINIMAX_REGION")) },
            fullRequestLogging = options.logRequests,
            requestLogDir = options.requestLogDir,
        )
    }
    if ("ollama" in selected) {
        providers += OllamaSubscriptionProxyProvider(
            apiKeyProvider = { env.value("OLLAMA_PROXY_API_KEY") ?: env.value("OLLAMA_API_KEY") },
            fullRequestLogging = options.logRequests,
            requestLogDir = options.requestLogDir,
        )
    }
    if ("opencode" in selected || "opencode-zen" in selected || "zen" in selected) {
        providers += OpenCodeZenSubscriptionProxyProvider(
            apiKeyProvider = { env.value("OPENCODE_PROXY_API_KEY") ?: env.value("OPENCODE_API_KEY") },
            fullRequestLogging = options.logRequests,
            requestLogDir = options.requestLogDir,
        )
    }
    if ("zai" in selected || "z.ai" in selected || "zhipu" in selected) {
        providers += ZaiSubscriptionProxyProvider(
            apiKeyProvider = { env.value("ZAI_PROXY_API_KEY") ?: env.value("ZAI_API_KEY") ?: env.value("ZHIPU_API_KEY") },
            fullRequestLogging = options.logRequests,
            requestLogDir = options.requestLogDir,
        )
    }
    return providers
}

private fun miniMaxRegion(value: String?): MiniMaxRegion {
    return if (value.equals("cn", ignoreCase = true)) MiniMaxRegion.CN else MiniMaxRegion.GLOBAL
}

private fun printModels(providers: List<SubscriptionProxyProvider>) {
    val catalog = SubscriptionModelCatalog(providers)
    if (catalog.models.isEmpty()) {
        println("No models available. Check selected providers and credentials.")
        return
    }
    catalog.models.forEach { model ->
        println("${model.localId}\t${model.providerId}\t${model.upstreamId}")
    }
}

private fun saveGitHubCredentialsToDotEnv(path: Path, accessToken: String, envLocalApiKey: String?) {
    val existing = loadDotEnvValues(path).toMutableMap()
    existing["GITHUB_COPILOT_PROXY_ACCESS_TOKEN"] = accessToken
    existing["SUBSCRIPTION_PROXY_API_KEY"] = envLocalApiKey
        ?: existing["SUBSCRIPTION_PROXY_API_KEY"]
        ?: DEFAULT_LOCAL_API_KEY
    existing.putIfAbsent("SUBSCRIPTION_PROXY_PORT", DEFAULT_PORT.toString())
    restrictToOwner(path)
    Files.writeString(path, existing.entries.joinToString("\n", postfix = "\n") { (key, value) ->
        "$key=${value.toDotEnvValue()}"
    })
    println("Saved GitHub Copilot proxy credentials to ${path.toAbsolutePath().normalize()}")
}

private fun parseProviders(value: String): Set<String> {
    return parseCommaSeparatedList(value).map { it.lowercase() }.toSet()
}

private fun parseCommaSeparatedList(value: String?): List<String> {
    return value
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()
}

private fun loadDotEnvValues(path: Path): Map<String, String> {
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

private fun String.unquoteDotEnvValue(): String {
    if (length >= 2 && ((first() == '"' && last() == '"') || (first() == '\'' && last() == '\''))) {
        return substring(1, length - 1)
    }
    return this
}

internal class StandaloneEnv private constructor(
    private val fileValues: Map<String, String>,
    private val processValues: Map<String, String>,
) {
    fun value(name: String): String? {
        return processValues[name]?.takeIf { it.isNotBlank() }
            ?: fileValues[name]?.takeIf { it.isNotBlank() }
    }

    companion object {
        fun load(path: Path): StandaloneEnv = StandaloneEnv(loadDotEnv(path), System.getenv())

        fun of(values: Map<String, String>): StandaloneEnv = StandaloneEnv(values, emptyMap())

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
    }
}

private const val DEFAULT_PORT = 14623
private const val DEFAULT_LOCAL_API_KEY = "sk-quota-subscription-local"
private const val DEFAULT_REQUEST_LOG_DIR = "logs/subscription-proxy-requests"
private val DEFAULT_ENV_FILE: Path = Path.of(".env")
