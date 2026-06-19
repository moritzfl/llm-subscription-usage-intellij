package de.moritzf.quota.idea.common

import com.intellij.ide.ActivityTracker
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.idea.ui.indicator.QuotaIndicatorData
import de.moritzf.quota.idea.ui.indicator.QuotaIndicatorSource
import de.moritzf.quota.shared.ProviderQuota
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
        ZaiQuotaProvider(),
        MiniMaxQuotaProvider(),
        KimiQuotaProvider(),
        GitHubQuotaProvider(),
        CursorQuotaProvider(),
        SuperGrokQuotaProvider(),
    ),
    private val settingsProvider: () -> QuotaSettingsState? = {
        runCatching { QuotaSettingsState.getInstance() }.getOrNull()
    },
    private val scheduler: ScheduledExecutorService = AppExecutorUtil.getAppScheduledExecutorService(),
    private val updatePublisher: (QuotaUsageSnapshot) -> Unit = { snapshot ->
        ApplicationManager.getApplication().invokeLater {
            val publisher = ApplicationManager.getApplication().messageBus
                .syncPublisher(QuotaUsageListener.TOPIC)
            snapshot.entries.forEach { (type, entry) ->
                publisher.onQuotaUpdated(type, entry.quota, entry.error)
            }
            ActivityTracker.getInstance().inc()
        }
    },
    scheduleOnInit: Boolean = true,
) : Disposable {
    private class ProviderState(val provider: QuotaProvider) {
        val refreshing = AtomicBoolean(false)
    }

    private val states: Map<QuotaProviderType, ProviderState> =
        providers.associateBy({ it.type }, ::ProviderState)
    private var scheduled: ScheduledFuture<*>? = null

    init {
        hydrateCachedQuotas()
        if (scheduleOnInit) {
            scheduleRefresh()
        }
    }

    fun provider(type: QuotaProviderType): QuotaProvider? = states[type]?.provider

    fun getLastQuota(type: QuotaProviderType): ProviderQuota? = provider(type)?.getLastQuota()

    fun getLastError(type: QuotaProviderType): String? = provider(type)?.getLastError()

    fun getLastResponseJson(type: QuotaProviderType): String? = provider(type)?.getLastRawJson()

    fun currentSnapshot(): QuotaUsageSnapshot {
        return QuotaUsageSnapshot(
            states.mapValues { (_, state) ->
                ProviderSnapshot(state.provider.getLastQuota(), state.provider.getLastError())
            },
        )
    }

    internal fun getEffectiveIndicatorData(): QuotaIndicatorData {
        val settings = settingsProvider()
        val source = when (val configured = settings?.source() ?: QuotaIndicatorSource.OPEN_AI) {
            QuotaIndicatorSource.LAST_USED -> resolveLastActiveSource(settings)
            else -> configured
        }
        val type = source.providerType ?: QuotaProviderType.OPEN_AI
        return QuotaIndicatorData(type, getLastQuota(type), getLastError(type))
    }

    fun refreshNowAsync() {
        AppExecutorUtil.getAppExecutorService().execute(::refreshNow)
    }

    fun refreshNowBlocking() {
        refreshNow()
    }

    fun refreshAsync(type: QuotaProviderType) {
        AppExecutorUtil.getAppExecutorService().execute { refreshProvider(type) }
    }

    fun refreshBlocking(type: QuotaProviderType) {
        refreshProvider(type)
    }

    fun clearAllUsageData(openAiError: String? = null) {
        clearUsageData(QuotaProviderType.OPEN_AI, openAiError)
        states.keys.filter { it != QuotaProviderType.OPEN_AI }.forEach { clearUsageData(it) }
    }

    fun clearUsageData(type: QuotaProviderType, error: String? = null) {
        val provider = provider(type) ?: return
        provider.clearData(error ?: provider.notConfiguredMessage)
        settingsProvider()?.setCachedQuotaJson(type, null)
        publishUpdate()
    }

    fun resetOpenCodeWorkspaceCache() {
        (provider(QuotaProviderType.OPEN_CODE) as? OpenCodeQuotaProvider)?.resetWorkspaceCache()
    }

    fun consumeOpenAiResetCredit(creditId: String?) {
        (provider(QuotaProviderType.OPEN_AI) as? OpenAiQuotaProvider)?.consumeResetCredit(creditId)
        refreshProvider(QuotaProviderType.OPEN_AI)
    }

    private fun scheduleRefresh() {
        val minutes = maxOf(1, settingsProvider()?.refreshMinutes ?: 5)
        scheduled = scheduler.scheduleWithFixedDelay(::refreshNow, 0, minutes.toLong(), TimeUnit.MINUTES)
    }

    private fun hydrateCachedQuotas() {
        val settings = settingsProvider() ?: return
        states.values.forEach { it.provider.hydrateFromCache(settings) }
    }

    private fun refreshNow() {
        val executor = AppExecutorUtil.getAppExecutorService()
        val futures = states.keys.map { type ->
            executor.submit { refreshProvider(type) }
        }
        futures.forEach { future ->
            runCatching { future.get() }
                .onFailure { LOG.warn("Quota provider refresh failed", it) }
        }
    }

    private fun refreshProvider(type: QuotaProviderType) {
        val state = states[type] ?: return
        if (!state.refreshing.compareAndSet(false, true)) {
            return
        }

        try {
            val provider = state.provider
            val settings = settingsProvider()
            val oldFraction = settings?.let(provider::cachedUsageFraction)
            provider.refresh()
            val newFraction = provider.currentUsageFraction()

            val significantChange = oldFraction != null && newFraction != null &&
                kotlin.math.abs(newFraction - oldFraction) >= MIN_USAGE_INCREASE

            if (significantChange && newFraction > oldFraction) {
                settings.lastActiveSource = provider.type.id
            }

            if (oldFraction == null || significantChange) {
                settings?.let(provider::persistToCache)
            }
            publishUpdate()
        } finally {
            state.refreshing.set(false)
        }
    }

    private fun resolveLastActiveSource(settings: QuotaSettingsState?): QuotaIndicatorSource {
        val active = settings?.lastActiveSource
        if (!active.isNullOrBlank()) {
            return QuotaIndicatorSource.fromStorageValue(active)
        }
        return settings?.lastUsedSource() ?: QuotaIndicatorSource.OPEN_AI
    }

    private fun publishUpdate() {
        updatePublisher(currentSnapshot())
    }

    override fun dispose() {
        scheduled?.cancel(true)
        scheduled = null
    }

    companion object {
        private val LOG = Logger.getInstance(QuotaUsageService::class.java)
        private const val MIN_USAGE_INCREASE = 0.005

        @JvmStatic
        fun getInstance(): QuotaUsageService {
            return ApplicationManager.getApplication().getService(QuotaUsageService::class.java)
        }
    }
}
