package de.moritzf.quota.idea.openai

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import de.moritzf.proxy.subscription.SubscriptionModelCatalog
import de.moritzf.proxy.subscription.SubscriptionProxyModel
import de.moritzf.proxy.subscription.SubscriptionProxyProvider
import de.moritzf.proxy.subscription.SubscriptionProxyServer
import de.moritzf.proxy.util.ApiKeyUtils
import de.moritzf.quota.github.GitHubQuotaClient
import de.moritzf.quota.github.proxy.GitHubCopilotSubscriptionProxyProvider
import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.github.GitHubCredentialsStore
import de.moritzf.quota.idea.kimi.KimiCredentialsStore
import de.moritzf.quota.idea.minimax.MiniMaxApiKeyStore
import de.moritzf.quota.idea.ollama.OllamaApiKeyStore
import de.moritzf.quota.idea.opencode.OpenCodeApiKeyStore
import de.moritzf.quota.idea.settings.QuotaSettingsListener
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.idea.zai.ZaiApiKeyStore
import de.moritzf.quota.kimi.proxy.KimiSubscriptionProxyProvider
import de.moritzf.quota.minimax.MiniMaxRegion
import de.moritzf.quota.minimax.MiniMaxRegionPreference
import de.moritzf.quota.minimax.proxy.MiniMaxSubscriptionProxyProvider
import de.moritzf.quota.ollama.proxy.OllamaSubscriptionProxyProvider
import de.moritzf.quota.openai.proxy.OpenAiCodexSubscriptionProxyProvider
import de.moritzf.quota.opencode.proxy.OpenCodeZenSubscriptionProxyProvider
import de.moritzf.quota.supergrok.proxy.SuperGrokSubscriptionProxyProvider
import de.moritzf.quota.zai.proxy.ZaiSubscriptionProxyProvider
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.Executor

@Service(Service.Level.APP)
class OpenAiProxyService(
    private val settingsProvider: () -> QuotaSettingsState? = {
        runCatching { QuotaSettingsState.getInstance() }.getOrNull()
    },
    private val apiKeyStore: OpenAiProxyApiKeyStore = OpenAiProxyApiKeyStore.getInstance(),
    private val authServiceProvider: () -> QuotaAuthService = { QuotaAuthService.getInstance() },
    private val githubCredentialsStoreProvider: () -> GitHubCredentialsStore = { GitHubCredentialsStore.getInstance() },
    private val kimiCredentialsStoreProvider: () -> KimiCredentialsStore = { KimiCredentialsStore.getInstance() },
    private val miniMaxApiKeyStoreProvider: () -> MiniMaxApiKeyStore = { MiniMaxApiKeyStore.getInstance() },
    private val ollamaApiKeyStoreProvider: () -> OllamaApiKeyStore = { OllamaApiKeyStore.getInstance() },
    private val openCodeApiKeyStoreProvider: () -> OpenCodeApiKeyStore = { OpenCodeApiKeyStore.getInstance() },
    private val zaiApiKeyStoreProvider: () -> ZaiApiKeyStore = { ZaiApiKeyStore.getInstance() },
    private val executor: Executor = AppExecutorUtil.getAppExecutorService(),
    subscribeToSettings: Boolean = true,
) : Disposable {
    private val lock = Any()
    @Volatile private var server: SubscriptionProxyServer? = null
    @Volatile private var runningPort: Int? = null
    @Volatile private var runningApiKeyFingerprint: String? = null
    @Volatile private var runningLogRequests: Boolean = false
    @Volatile private var runningProviderIds: Set<String> = emptySet()
    @Volatile private var lastError: String? = null

    init {
        if (subscribeToSettings) {
            ApplicationManager.getApplication().messageBus.connect(this)
                .subscribe(QuotaSettingsListener.TOPIC, QuotaSettingsListener { reloadFromSettings() })
            reloadFromSettings()
        }
    }

    fun reloadFromSettings() {
        executor.execute(::applySettings)
    }

    fun status(): OpenAiProxyStatus {
        val settings = settingsProvider()
        val enabled = settings?.openAiProxyEnabled == true
        val port = sanitizePort(settings?.openAiProxyPort ?: DEFAULT_PORT)
        val running = server?.isRunning == true
        val enabledProviders = settings?.enabledSubscriptionProxyProviders().orEmpty()
        return OpenAiProxyStatus(
            enabled = enabled,
            running = running,
            baseUrl = localBaseUrl(runningPort ?: port),
            error = lastError,
            enabledProviders = enabledProviders.map { it.id }.toSet(),
            runningProviders = runningProviderIds,
            requestLogDir = requestLogDir().toString(),
        )
    }

    fun advertisedModelsSnapshot(): List<SubscriptionProxyModel> {
        val settings = settingsProvider() ?: return emptyList()
        return SubscriptionModelCatalog(createProviders(settings, false, requestLogDir().toString())).models
    }

    fun requestLogDir(): Path = DEFAULT_REQUEST_LOG_DIR

    private fun applySettings() {
        val settings = settingsProvider()
        val enabled = settings?.openAiProxyEnabled == true
        val port = sanitizePort(settings?.openAiProxyPort ?: DEFAULT_PORT)
        val logRequests = settings?.openAiProxyLogRequests == true
        val providerIds = settings?.enabledSubscriptionProxyProviders().orEmpty().map { it.id }.toSet()

        synchronized(lock) {
            if (!enabled) {
                stopLocked()
                lastError = null
                return
            }

            try {
                val localApiKey = apiKeyStore.ensureApiKeyBlocking()
                val localApiKeyFingerprint = ApiKeyUtils.fingerprint(localApiKey)
                if (server?.isRunning == true && runningPort == port &&
                    runningApiKeyFingerprint == localApiKeyFingerprint && runningLogRequests == logRequests &&
                    runningProviderIds == providerIds
                ) {
                    lastError = null
                    return
                }

                stopLocked()
                // Build providers once per proxy lifetime so in-memory model caches stay warm.
                val providers = createProviders(settings, logRequests, requestLogDir().toString())
                val proxyServer = SubscriptionProxyServer(
                    port = port,
                    localApiKeyProvider = { localApiKey },
                    providers = { providers },
                    fullRequestLogging = logRequests,
                    requestLogDir = requestLogDir().toString(),
                )
                proxyServer.start()
                server = proxyServer
                runningPort = port
                runningApiKeyFingerprint = localApiKeyFingerprint
                runningLogRequests = logRequests
                runningProviderIds = providerIds
                lastError = null
                LOG.info("Subscription proxy started at ${localBaseUrl(port)}")
            } catch (exception: Exception) {
                lastError = exception.message ?: exception::class.java.simpleName
                LOG.warn("Failed to start subscription proxy", exception)
                stopLocked()
            }
        }
    }

    private fun createProviders(
        settings: QuotaSettingsState,
        logRequests: Boolean,
        requestLogDir: String,
    ): List<SubscriptionProxyProvider> {
        val enabledProviders = settings.enabledSubscriptionProxyProviders()
        return buildList {
            if (QuotaProviderType.OPEN_AI in enabledProviders) {
                add(
                    OpenAiCodexSubscriptionProxyProvider(
                        accessTokenProvider = { authServiceProvider().getAccessTokenBlocking(QuotaProviderType.OPEN_AI) },
                        accountIdProvider = { authServiceProvider().getAccountId(QuotaProviderType.OPEN_AI) },
                        // Upstream 401s route back to the IDE auth service, which owns refresh
                        // and persistence; the stale token lets it dedupe concurrent refreshes.
                        tokenRefresher = { staleToken ->
                            authServiceProvider().forceRefreshBlocking(QuotaProviderType.OPEN_AI, staleToken)
                        },
                        fullRequestLogging = logRequests,
                        requestLogDir = requestLogDir,
                    ),
                )
            }
            if (QuotaProviderType.SUPERGROK in enabledProviders) {
                add(
                    SuperGrokSubscriptionProxyProvider(
                        accessTokenProvider = { authServiceProvider().getAccessTokenBlocking(QuotaProviderType.SUPERGROK) },
                        tokenRefresher = { staleToken ->
                            authServiceProvider().forceRefreshBlocking(QuotaProviderType.SUPERGROK, staleToken)
                        },
                        fullRequestLogging = logRequests,
                        requestLogDir = requestLogDir,
                    ),
                )
            }
            if (QuotaProviderType.GITHUB in enabledProviders) {
                add(
                    GitHubCopilotSubscriptionProxyProvider(
                        accessTokenProvider = { githubCredentialsStoreProvider().loadBlocking()?.accessToken },
                        upstreamBaseUri = githubCopilotBaseUri(settings.githubEnterpriseHost),
                        persistentModelCacheProvider = {
                            settings.subscriptionProxyModelCatalogJson(GitHubCopilotSubscriptionProxyProvider.ID)
                        },
                        persistentModelCacheSaver = { json ->
                            settings.setSubscriptionProxyModelCatalogJson(GitHubCopilotSubscriptionProxyProvider.ID, json)
                        },
                        fullRequestLogging = logRequests,
                        requestLogDir = requestLogDir,
                    ),
                )
            }
            if (QuotaProviderType.KIMI in enabledProviders) {
                add(
                    KimiSubscriptionProxyProvider(
                        credentialsProvider = { kimiCredentialsStoreProvider().loadBlocking() },
                        credentialsSaver = { credentials -> kimiCredentialsStoreProvider().save(credentials) },
                        fullRequestLogging = logRequests,
                        requestLogDir = requestLogDir,
                    ),
                )
            }
            if (QuotaProviderType.MINIMAX in enabledProviders) {
                add(
                    MiniMaxSubscriptionProxyProvider(
                        apiKeyProvider = { miniMaxApiKeyStoreProvider().loadBlocking() },
                        regionProvider = { miniMaxProxyRegion(settings.miniMaxRegionPreference()) },
                        fullRequestLogging = logRequests,
                        requestLogDir = requestLogDir,
                    ),
                )
            }
            if (QuotaProviderType.OLLAMA in enabledProviders) {
                add(
                    OllamaSubscriptionProxyProvider(
                        apiKeyProvider = { ollamaApiKeyStoreProvider().loadBlocking() },
                        fullRequestLogging = logRequests,
                        requestLogDir = requestLogDir,
                    ),
                )
            }
            if (QuotaProviderType.OPEN_CODE in enabledProviders) {
                add(
                    OpenCodeZenSubscriptionProxyProvider(
                        apiKeyProvider = { openCodeApiKeyStoreProvider().loadBlocking() },
                        fullRequestLogging = logRequests,
                        requestLogDir = requestLogDir,
                    ),
                )
            }
            if (QuotaProviderType.ZAI in enabledProviders) {
                add(
                    ZaiSubscriptionProxyProvider(
                        apiKeyProvider = { zaiApiKeyStoreProvider().loadBlocking() },
                        fullRequestLogging = logRequests,
                        requestLogDir = requestLogDir,
                    ),
                )
            }
        }
    }

    private fun githubCopilotBaseUri(enterpriseHost: String): URI {
        val host = GitHubQuotaClient.normalizedEnterpriseHost(enterpriseHost)
        if (host == "github.com") return GitHubCopilotSubscriptionProxyProvider.DEFAULT_UPSTREAM_BASE_URI
        return URI.create("https://copilot-api.$host")
    }

    private fun miniMaxProxyRegion(preference: MiniMaxRegionPreference): MiniMaxRegion {
        return when (preference) {
            MiniMaxRegionPreference.CN -> MiniMaxRegion.CN
            MiniMaxRegionPreference.GLOBAL,
            MiniMaxRegionPreference.AUTO -> MiniMaxRegion.GLOBAL
        }
    }

    private fun stopLocked() {
        server?.stop()
        server = null
        runningPort = null
        runningApiKeyFingerprint = null
        runningLogRequests = false
        runningProviderIds = emptySet()
    }

    override fun dispose() {
        synchronized(lock) {
            stopLocked()
        }
    }

    companion object {
        const val DEFAULT_PORT = 14621
        private val DEFAULT_REQUEST_LOG_DIR: Path = Path.of(
            System.getProperty("java.io.tmpdir"),
            "openai-usage-quota-intellij",
            "subscription-proxy-requests",
        )
        private val LOG = Logger.getInstance(OpenAiProxyService::class.java)

        @JvmStatic
        fun getInstance(): OpenAiProxyService {
            return ApplicationManager.getApplication().getService(OpenAiProxyService::class.java)
        }

        // No /v1 suffix: LiteLLM-style clients (Junie included) append /v1/... themselves,
        // and the proxy serves all routes both with and without the prefix.
        fun localBaseUrl(port: Int): String = "http://127.0.0.1:${sanitizePort(port)}"

        fun sanitizePort(port: Int): Int = port.coerceIn(1, 65535)
    }
}

data class OpenAiProxyStatus(
    val enabled: Boolean,
    val running: Boolean,
    val baseUrl: String,
    val error: String?,
    val enabledProviders: Set<String> = emptySet(),
    val runningProviders: Set<String> = emptySet(),
    val requestLogDir: String = "",
)
