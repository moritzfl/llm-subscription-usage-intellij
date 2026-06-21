package de.moritzf.proxy.subscription

import de.moritzf.quota.github.proxy.GitHubCopilotSubscriptionProxyProvider
import de.moritzf.quota.openai.proxy.OpenAiCodexSubscriptionProxyProvider
import de.moritzf.quota.supergrok.proxy.SuperGrokSubscriptionProxyProvider
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch

fun main(args: Array<String>) {
    val options = parseStandaloneSubscriptionOptions(args)
    val env = StandaloneEnv.load(options.envFile)
    val localApiKey = env.value("SUBSCRIPTION_PROXY_API_KEY") ?: DEFAULT_LOCAL_API_KEY
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
    val providers: Set<String> = setOf("openai", "supergrok", "github"),
    val allowAnyCors: Boolean = false,
    val corsOrigins: List<String> = emptyList(),
    val logRequests: Boolean = false,
    val requestLogDir: String = DEFAULT_REQUEST_LOG_DIR,
    val listModels: Boolean = false,
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
            else -> error("Unknown argument: $arg")
        }
        index += 1
    }
    require(options.port in 1..65535) { "Port must be in range 1-65535, got: ${options.port}" }
    return options
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
    return providers
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
