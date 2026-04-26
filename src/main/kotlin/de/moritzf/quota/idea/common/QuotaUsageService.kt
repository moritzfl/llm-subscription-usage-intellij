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
    ),
    private val settingsProvider: () -> QuotaSettingsState? = {
        runCatching { QuotaSettingsState.getInstance() }.getOrNull()
    },
    private val scheduler: ScheduledExecutorService = AppExecutorUtil.getAppScheduledExecutorService(),
    private val updatePublisher: (OpenAiCodexQuota?, String?, OpenCodeQuota?, String?) -> Unit = { quota, error, openCodeQuota, openCodeError ->
        ApplicationManager.getApplication().invokeLater {
            val publisher = ApplicationManager.getApplication().messageBus
                .syncPublisher(QuotaUsageListener.TOPIC)
            publisher.onQuotaUpdated(quota, error)
            publisher.onOpenCodeQuotaUpdated(openCodeQuota, openCodeError)
            ActivityTracker.getInstance().inc()
        }
    },
    scheduleOnInit: Boolean = true,
) : Disposable {
    private val refreshing = AtomicBoolean(false)
    private val openAiProvider = providers.filterIsInstance<OpenAiQuotaProvider>().firstOrNull()
    private val openCodeProvider = providers.filterIsInstance<OpenCodeQuotaProvider>().firstOrNull()
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

    internal fun getEffectiveIndicatorData(): QuotaIndicatorData {
        val settings = settingsProvider()
        val source = when (settings?.source() ?: QuotaIndicatorSource.OPEN_AI) {
            QuotaIndicatorSource.LAST_USED -> settings?.lastUsedSource() ?: QuotaIndicatorSource.OPEN_AI
            QuotaIndicatorSource.OPEN_AI -> QuotaIndicatorSource.OPEN_AI
            QuotaIndicatorSource.OPEN_CODE -> QuotaIndicatorSource.OPEN_CODE
        }

        return when (source) {
            QuotaIndicatorSource.OPEN_AI -> QuotaIndicatorData.OpenAi(getLastQuota(), getLastError())
            QuotaIndicatorSource.OPEN_CODE -> QuotaIndicatorData.OpenCode(getLastOpenCodeQuota(), getLastOpenCodeError())
            QuotaIndicatorSource.LAST_USED -> QuotaIndicatorData.OpenAi(getLastQuota(), getLastError())
        }
    }

    fun refreshNowAsync() {
        AppExecutorUtil.getAppExecutorService().execute(::refreshNow)
    }

    fun refreshNowBlocking() {
        refreshNow()
    }

    fun clearUsageData(error: String? = null) {
        clearCodexUsageData(error)
        clearOpenCodeUsageData()
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
    }

    private fun refreshNow() {
        if (!refreshing.compareAndSet(false, true)) {
            return
        }

        try {
            openAiProvider?.refresh()
            openCodeProvider?.refresh()
            persistToCache()
            publishUpdate()
        } finally {
            refreshing.set(false)
        }
    }

    private fun persistToCache() {
        val settings = settingsProvider() ?: return
        openAiProvider?.persistToCache(settings)
        openCodeProvider?.persistToCache(settings)
    }

    private fun publishUpdate() {
        updatePublisher(getLastQuota(), getLastError(), getLastOpenCodeQuota(), getLastOpenCodeError())
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
