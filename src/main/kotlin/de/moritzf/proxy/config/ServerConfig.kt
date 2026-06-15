package de.moritzf.proxy.config
import java.nio.file.Path
import java.util.Locale
class ServerConfig(
    host: String?,
    port: Int,
    models: List<String>?,
    val codexVersion: String?,
    baseUrl: String?,
    oauthClientId: String?,
    val oauthTokenUrl: String?,
    val oauthFilePath: String?,
    instructions: String?,
    val store: Boolean,
    apiKeys: Map<String, String>?,
    val adminKey: String?,
    val allowAnyCors: Boolean,
    allowedCorsOrigins: List<String>?,
    val fullRequestLogging: Boolean,
    requestLogDir: String?,
    val forwardPromptCacheHeaders: Boolean,
    codexInstructionsMode: String?,
    codexInstructionsCacheDir: String?,
    val enableResponsesReplayCache: Boolean,
    val consoleAccessLog: Boolean,
) {
    val host: String = host ?: DEFAULT_HOST
    val port: Int = port
    val models: List<String>? = models
    val baseUrl: String = baseUrl ?: DEFAULT_BASE_URL
    val oauthClientId: String = oauthClientId ?: DEFAULT_CLIENT_ID
    val instructions: String = instructions ?: DEFAULT_INSTRUCTIONS
    val apiKeys: Map<String, String> = java.util.Map.copyOf(apiKeys ?: emptyMap())
    val allowedCorsOrigins: List<String> = normalizeCorsOrigins(allowedCorsOrigins)
    val requestLogDir: String = normalizeRequestLogDir(requestLogDir)
    val codexInstructionsMode: String = normalizeCodexInstructionsMode(codexInstructionsMode)
    val codexInstructionsCacheDir: String = normalizeCodexInstructionsCacheDir(codexInstructionsCacheDir)
    constructor(
        host: String?,
        port: Int,
        models: List<String>?,
        codexVersion: String?,
        baseUrl: String?,
        oauthClientId: String?,
        oauthTokenUrl: String?,
        oauthFilePath: String?,
        instructions: String?,
        store: Boolean,
        apiKeys: Map<String, String>?,
        adminKey: String?,
    ) : this(
        host,
        port,
        models,
        codexVersion,
        baseUrl,
        oauthClientId,
        oauthTokenUrl,
        oauthFilePath,
        instructions,
        store,
        apiKeys,
        adminKey,
        false,
        emptyList(),
        false,
        null,
        false,
        null,
        null,
        false,
        true,
    )
    constructor(
        host: String?,
        port: Int,
        models: List<String>?,
        codexVersion: String?,
        baseUrl: String?,
        oauthClientId: String?,
        oauthTokenUrl: String?,
        oauthFilePath: String?,
        instructions: String?,
        store: Boolean,
        apiKeys: Map<String, String>?,
        adminKey: String?,
        allowAnyCors: Boolean,
        allowedCorsOrigins: List<String>?,
    ) : this(
        host,
        port,
        models,
        codexVersion,
        baseUrl,
        oauthClientId,
        oauthTokenUrl,
        oauthFilePath,
        instructions,
        store,
        apiKeys,
        adminKey,
        allowAnyCors,
        allowedCorsOrigins,
        false,
        null,
        false,
        null,
        null,
        false,
        true,
    )
    constructor(
        host: String?,
        port: Int,
        models: List<String>?,
        codexVersion: String?,
        baseUrl: String?,
        oauthClientId: String?,
        oauthTokenUrl: String?,
        oauthFilePath: String?,
        instructions: String?,
        store: Boolean,
        apiKeys: Map<String, String>?,
        adminKey: String?,
        allowAnyCors: Boolean,
        allowedCorsOrigins: List<String>?,
        fullRequestLogging: Boolean,
        requestLogDir: String?,
        forwardPromptCacheHeaders: Boolean,
    ) : this(
        host,
        port,
        models,
        codexVersion,
        baseUrl,
        oauthClientId,
        oauthTokenUrl,
        oauthFilePath,
        instructions,
        store,
        apiKeys,
        adminKey,
        allowAnyCors,
        allowedCorsOrigins,
        fullRequestLogging,
        requestLogDir,
        forwardPromptCacheHeaders,
        null,
        null,
        false,
        true,
    )
    init {
        if (port !in 1..65535) {
            throw IllegalArgumentException("Port must be in range 1-65535, got: $port")
        }
        if (!isLocalOnlyHost(this.host) && this.apiKeys.isEmpty() && adminKey == null) {
            throw IllegalArgumentException(
                "API key enforcement is required when binding to a non-loopback host: ${this.host}",
            )
        }
    }
    fun requiresApiKeyEnforcement(): Boolean = !isLocalOnlyHost(host)
    companion object {
        const val DEFAULT_HOST: String = "127.0.0.1"
        const val DEFAULT_PORT: Int = 10531
        const val DEFAULT_BASE_URL: String = "https://chatgpt.com/backend-api/codex"
        const val DEFAULT_CLIENT_ID: String = "app_EMoamEEZ73f0CkXaXp7hrann"
        const val DEFAULT_ISSUER: String = "https://auth.openai.com"
        const val DEFAULT_INSTRUCTIONS: String = ""
        const val DEFAULT_MODEL: String = "gpt-5.5"
        val DEFAULT_REQUEST_LOG_DIR: String = Path.of("logs", "requests")
            .toAbsolutePath()
            .normalize()
            .toString()
        const val DEFAULT_CODEX_INSTRUCTIONS_MODE: String = "configured"
        val DEFAULT_CODEX_INSTRUCTIONS_CACHE_DIR: String = Path.of("cache", "codex-instructions")
            .toAbsolutePath()
            .normalize()
            .toString()
        const val KEY_PREFIX: String = "sk-proxy-"
        private fun normalizeCorsOrigins(origins: List<String>?): List<String> {
            return origins
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
        }
        private fun normalizeRequestLogDir(requestLogDir: String?): String {
            if (requestLogDir.isNullOrBlank()) {
                return DEFAULT_REQUEST_LOG_DIR
            }
            return Path.of(requestLogDir).toAbsolutePath().normalize().toString()
        }
        private fun normalizeCodexInstructionsMode(mode: String?): String {
            if (mode.isNullOrBlank()) {
                return DEFAULT_CODEX_INSTRUCTIONS_MODE
            }
            val normalized = mode.trim().lowercase(Locale.ROOT)
            if (normalized != "configured" && normalized != "latest-codex") {
                throw IllegalArgumentException("Codex instructions mode must be configured or latest-codex, got: $mode")
            }
            return normalized
        }
        private fun normalizeCodexInstructionsCacheDir(cacheDir: String?): String {
            if (cacheDir.isNullOrBlank()) {
                return DEFAULT_CODEX_INSTRUCTIONS_CACHE_DIR
            }
            return Path.of(cacheDir).toAbsolutePath().normalize().toString()
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
    }
}
