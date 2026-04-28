package de.moritzf.quota.idea.common

import com.intellij.ide.ActivityTracker
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.util.concurrency.AppExecutorUtil
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.idea.ui.indicator.QuotaIndicatorData
import de.moritzf.quota.idea.ui.indicator.QuotaIndicatorSource
import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.ollama.OllamaQuota
import de.moritzf.quota.shared.JsonSupport
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Periodically fetches quota data from all registered providers and publishes updates to the IDE message bus.
 */
@Service(Service.Level.APP)
class QuotaUsageService(
    providers: List<QuotaProvider> = listOf(
        OpenAiQuotaProvider(),
        OpenCodeQuotaProvider(),
        OllamaQuotaProvider(),
    ),
    private val settingsProvider: () -> QuotaSettingsState? = {
        runCatching { QuotaSettingsState.getInstance() }.getOrNull()
    },
    private val scheduler: ScheduledExecutorService = AppExecutorUtil.getAppScheduledExecutorService(),
    private val updatePublisher: (OpenAiCodexQuota?, String?, OpenCodeQuota?, String?, OllamaQuota?, String?) -> Unit = { quota, error, openCodeQuota, openCodeError, ollamaQuota, ollamaError ->
        ApplicationManager.getApplication().invokeLater {
            val publisher = ApplicationManager.getApplication().messageBus
                .syncPublisher(QuotaUsageListener.TOPIC)
            publisher.onQuotaUpdated(quota, error)
            publisher.onOpenCodeQuotaUpdated(openCodeQuota, openCodeError)
            publisher.onOllamaQuotaUpdated(ollamaQuota, ollamaError)
            ActivityTracker.getInstance().inc()
        }
    },
    scheduleOnInit: Boolean = true,
) : Disposable {
    private val refreshingOpenAi = AtomicBoolean(false)
    private val refreshingOpenCode = AtomicBoolean(false)
    private val refreshingOllama = AtomicBoolean(false)
    private val openAiProvider = providers.filterIsInstance<OpenAiQuotaProvider>().firstOrNull()
    private val openCodeProvider = providers.filterIsInstance<OpenCodeQuotaProvider>().firstOrNull()
    private val ollamaProvider = providers.filterIsInstance<OllamaQuotaProvider>().firstOrNull()
    private var scheduled: ScheduledFuture<*>? = null

    init {
        hydrateCachedQuotas()
        if (scheduleOnInit) {
            scheduleRefresh()
        }
    }

    fun getLastQuota(): OpenAiCodexQuota? = openAiProvider?.getLastQuota()
    fun getLastError(): String? = openAiProvider?.getLastError()
    fun getLastResponseJson(): String? = openAiProvider?.getLastRawJson()

    fun getLastOpenCodeQuota(): OpenCodeQuota? = openCodeProvider?.getLastQuota()
    fun getLastOpenCodeError(): String? = openCodeProvider?.getLastError()
    fun getLastOpenCodeResponseJson(): String? = openCodeProvider?.getLastQuota()?.rawJson

    fun getLastOllamaQuota(): OllamaQuota? = ollamaProvider?.getLastQuota()
    fun getLastOllamaError(): String? = ollamaProvider?.getLastError()
    fun getLastOllamaResponseJson(): String? {
        val quota = ollamaProvider?.getLastQuota() ?: return null
        return runCatching { JsonSupport.json.encodeToString(OllamaQuota.serializer(), quota) }.getOrNull()
    }

    internal fun getEffectiveIndicatorData(): QuotaIndicatorData {
        val settings = settingsProvider()
        val source = when (settings?.source() ?: QuotaIndicatorSource.OPEN_AI) {
            QuotaIndicatorSource.LAST_USED -> settings?.lastUsedSource() ?: QuotaIndicatorSource.OPEN_AI
            QuotaIndicatorSource.OPEN_AI -> QuotaIndicatorSource.OPEN_AI
            QuotaIndicatorSource.OPEN_CODE -> QuotaIndicatorSource.OPEN_CODE
            QuotaIndicatorSource.OLLAMA -> QuotaIndicatorSource.OLLAMA
        }

        return when (source) {
            QuotaIndicatorSource.OPEN_AI -> QuotaIndicatorData.OpenAi(getLastQuota(), getLastError())
            QuotaIndicatorSource.OPEN_CODE -> QuotaIndicatorData.OpenCode(getLastOpenCodeQuota(), getLastOpenCodeError())
            QuotaIndicatorSource.OLLAMA -> QuotaIndicatorData.Ollama(getLastOllamaQuota(), getLastOllamaError())
            QuotaIndicatorSource.LAST_USED -> QuotaIndicatorData.OpenAi(getLastQuota(), getLastError())
        }
    }

    fun refreshNowAsync() {
        AppExecutorUtil.getAppExecutorService().execute(::refreshNow)
    }

    fun refreshOpenCodeAsync() {
        AppExecutorUtil.getAppExecutorService().execute(::refreshOpenCode)
    }

    fun refreshOllamaAsync() {
        AppExecutorUtil.getAppExecutorService().execute(::refreshOllama)
    }

    fun refreshNowBlocking() {
        refreshNow()
    }

    fun refreshOpenAiBlocking() {
        refreshOpenAi()
    }

    fun refreshOpenCodeBlocking() {
        refreshOpenCode()
    }

    fun refreshOllamaBlocking() {
        refreshOllama()
    }

    fun clearUsageData(error: String? = null) {
        clearCodexUsageData(error)
        clearOpenCodeUsageData()
        clearOllamaUsageData()
    }

    fun clearCodexUsageData(error: String? = null) {
        settingsProvider()?.cachedOpenAiQuotaJson = null
        openAiProvider?.clearData(error)
        publishUpdate()
    }

    fun clearOpenCodeUsageData(error: String? = "No session cookie configured") {
        openCodeProvider?.clearData(error)
        settingsProvider()?.cachedOpenCodeQuotaJson = null
        publishUpdate()
    }

    fun clearOllamaUsageData(error: String? = "No session cookie configured") {
        ollamaProvider?.clearData(error)
        settingsProvider()?.cachedOllamaQuotaJson = null
        publishUpdate()
    }

    fun resetOpenCodeWorkspaceCache() {
        openCodeProvider?.resetWorkspaceCache()
    }

    private fun scheduleRefresh() {
        val minutes = maxOf(1, settingsProvider()?.refreshMinutes ?: 5)
        scheduled = scheduler.scheduleWithFixedDelay(::refreshNow, 0, minutes.toLong(), TimeUnit.MINUTES)
    }

    private fun hydrateCachedQuotas() {
        val settings = settingsProvider() ?: return
        openAiProvider?.hydrateFromCache(settings)
        openCodeProvider?.hydrateFromCache(settings)
        ollamaProvider?.hydrateFromCache(settings)
    }

    private fun refreshNow() {
        refreshOpenAi()
        refreshOpenCode()
        refreshOllama()
    }

    private fun refreshOpenAi() {
        refreshProvider(openAiProvider, refreshingOpenAi)
    }

    private fun refreshOpenCode() {
        refreshProvider(openCodeProvider, refreshingOpenCode)
    }

    private fun refreshOllama() {
        refreshProvider(ollamaProvider, refreshingOllama)
    }

    private fun refreshProvider(provider: QuotaProvider?, refreshing: AtomicBoolean) {
        if (provider == null || !refreshing.compareAndSet(false, true)) {
            return
        }

        try {
            provider.refresh()
            settingsProvider()?.let(provider::persistToCache)
            publishUpdate()
        } finally {
            refreshing.set(false)
        }
    }

    private fun publishUpdate() {
        updatePublisher(
            getLastQuota(), getLastError(),
            getLastOpenCodeQuota(), getLastOpenCodeError(),
            getLastOllamaQuota(), getLastOllamaError()
        )
    }

    override fun dispose() {
        scheduled?.cancel(true)
        scheduled = null
    }

    companion object {
        @JvmStatic
        fun getInstance(): QuotaUsageService {
            return ApplicationManager.getApplication().getService(QuotaUsageService::class.java)
        }
    }
}
