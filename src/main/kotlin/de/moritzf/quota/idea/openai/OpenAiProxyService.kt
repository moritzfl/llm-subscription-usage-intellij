package de.moritzf.quota.idea.openai

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.settings.QuotaSettingsListener
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.openai.proxy.OpenAiProxyServer
import java.util.concurrent.Executor

@Service(Service.Level.APP)
class OpenAiProxyService(
    private val settingsProvider: () -> QuotaSettingsState? = {
        runCatching { QuotaSettingsState.getInstance() }.getOrNull()
    },
    private val apiKeyStore: OpenAiProxyApiKeyStore = OpenAiProxyApiKeyStore.getInstance(),
    private val authServiceProvider: () -> QuotaAuthService = { QuotaAuthService.getInstance() },
    private val executor: Executor = AppExecutorUtil.getAppExecutorService(),
    subscribeToSettings: Boolean = true,
) : Disposable {
    private val lock = Any()
    @Volatile private var server: OpenAiProxyServer? = null
    @Volatile private var runningPort: Int? = null
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
        return OpenAiProxyStatus(
            enabled = enabled,
            running = running,
            baseUrl = localBaseUrl(runningPort ?: port),
            error = lastError,
        )
    }

    private fun applySettings() {
        val settings = settingsProvider()
        val enabled = settings?.openAiProxyEnabled == true
        val port = sanitizePort(settings?.openAiProxyPort ?: DEFAULT_PORT)

        synchronized(lock) {
            if (!enabled) {
                stopLocked()
                lastError = null
                return
            }

            if (server?.isRunning == true && runningPort == port) {
                lastError = null
                return
            }

            stopLocked()
            try {
                apiKeyStore.ensureApiKeyBlocking()
                val proxyServer = OpenAiProxyServer(
                    port = port,
                    localApiKeyProvider = { apiKeyStore.loadBlocking() },
                    accessTokenProvider = { authServiceProvider().getAccessTokenBlocking(QuotaProviderType.OPEN_AI) },
                    accountIdProvider = { authServiceProvider().getAccountId(QuotaProviderType.OPEN_AI) },
                )
                proxyServer.start()
                server = proxyServer
                runningPort = port
                lastError = null
                LOG.info("OpenAI proxy started at ${localBaseUrl(port)}")
            } catch (exception: Exception) {
                lastError = exception.message ?: exception::class.java.simpleName
                LOG.warn("Failed to start OpenAI proxy", exception)
                stopLocked()
            }
        }
    }

    private fun stopLocked() {
        server?.stop()
        server = null
        runningPort = null
    }

    override fun dispose() {
        synchronized(lock) {
            stopLocked()
        }
    }

    companion object {
        const val DEFAULT_PORT = 14621
        private val LOG = Logger.getInstance(OpenAiProxyService::class.java)

        @JvmStatic
        fun getInstance(): OpenAiProxyService {
            return ApplicationManager.getApplication().getService(OpenAiProxyService::class.java)
        }

        fun localBaseUrl(port: Int): String = "http://127.0.0.1:${sanitizePort(port)}/v1"

        fun sanitizePort(port: Int): Int = port.coerceIn(1, 65535)
    }
}

data class OpenAiProxyStatus(
    val enabled: Boolean,
    val running: Boolean,
    val baseUrl: String,
    val error: String?,
)
