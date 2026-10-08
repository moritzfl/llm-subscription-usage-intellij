package de.moritzf.quota.idea.openai

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import de.moritzf.proxy.fim.CompletionsConfig
import de.moritzf.proxy.subscription.SubscriptionModelCatalog
import de.moritzf.proxy.subscription.SubscriptionProxyModel
import de.moritzf.proxy.subscription.SubscriptionProxyProvider
import de.moritzf.proxy.subscription.SubscriptionProxyServer
import de.moritzf.proxy.util.ApiKeyUtils
import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.common.IdeProxyBuildContext
import de.moritzf.quota.idea.common.ProviderCatalog
import de.moritzf.quota.idea.opencode.OpenCodeAuthService
import de.moritzf.quota.idea.settings.QuotaSettingsState
import java.nio.file.Path
import java.util.concurrent.Executor

@Service(Service.Level.APP)
class OpenAiProxyService(
    private val settingsProvider: () -> QuotaSettingsState? = {
        runCatching { QuotaSettingsState.getInstance() }.getOrNull()
    },
    private val apiKeyStore: OpenAiProxyApiKeyStore = OpenAiProxyApiKeyStore.getInstance(),
    private val authServiceProvider: () -> QuotaAuthService = { QuotaAuthService.getInstance() },
    private val openCodeAuthServiceProvider: () -> OpenCodeAuthService = {
        OpenCodeAuthService.getInstance()
    },
    private val executor: Executor = AppExecutorUtil.getAppExecutorService(),
) : Disposable {
    private val lock = Any()
    @Volatile private var server: SubscriptionProxyServer? = null
    @Volatile private var runningPort: Int? = null
    @Volatile private var runningApiKeyFingerprint: String? = null
    @Volatile private var runningLogRequests: Boolean = false
    @Volatile private var runningProviderIds: Set<String> = emptySet()
    @Volatile private var lastError: String? = null
    @Volatile private var disposed = false

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
        return SubscriptionModelCatalog(
                createProviders(settings, false, requestLogDir().toString())
            )
            .models
    }

    fun requestLogDir(): Path = DEFAULT_REQUEST_LOG_DIR

    private fun applySettings() {
        val settings = settingsProvider()
        val enabled = settings?.openAiProxyEnabled == true
        val port = sanitizePort(settings?.openAiProxyPort ?: DEFAULT_PORT)
        val logRequests = settings?.openAiProxyLogRequests == true
        val providerIds =
            settings?.enabledSubscriptionProxyProviders().orEmpty().map { it.id }.toSet()

        synchronized(lock) {
            if (disposed) {
                return
            }
            if (!enabled) {
                stopLocked()
                lastError = null
                return
            }

            try {
                val localApiKey = apiKeyStore.ensureApiKeyBlocking()
                val localApiKeyFingerprint = ApiKeyUtils.fingerprint(localApiKey)
                if (
                    server?.isRunning == true &&
                        runningPort == port &&
                        runningApiKeyFingerprint == localApiKeyFingerprint &&
                        runningLogRequests == logRequests &&
                        runningProviderIds == providerIds
                ) {
                    lastError = null
                    return
                }

                stopLocked()
                // Build providers once per proxy lifetime so in-memory model caches stay warm.
                val providers = createProviders(settings, logRequests, requestLogDir().toString())
                val proxyServer =
                    SubscriptionProxyServer(
                        port = port,
                        localApiKeyProvider = { localApiKey },
                        providers = { providers },
                        fullRequestLogging = logRequests,
                        requestLogDir = requestLogDir().toString(),
                        completionsConfig = {
                            settingsProvider()?.completionsConfig() ?: CompletionsConfig.DISABLED
                        },
                        mediaOperations = IdeMediaOperations(),
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
        val context =
            IdeProxyBuildContext(
                settings = settings,
                logRequests = logRequests,
                requestLogDir = requestLogDir,
                authService = authServiceProvider,
                openCodeAuth = openCodeAuthServiceProvider,
            )
        return ProviderCatalog.createIdeProxyProviders(
            context = context,
            enabled = settings.enabledSubscriptionProxyProviders().toSet(),
        )
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
            disposed = true
            stopLocked()
        }
    }

    companion object {
        const val DEFAULT_PORT = 14621
        private val DEFAULT_REQUEST_LOG_DIR: Path =
            Path.of(
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
