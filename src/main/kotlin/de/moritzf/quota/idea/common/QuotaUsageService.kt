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
import de.moritzf.quota.zai.ZaiQuota
import de.moritzf.quota.minimax.MiniMaxQuota
import de.moritzf.quota.kimi.KimiQuota
import de.moritzf.quota.cursor.CursorQuota
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
        CursorQuotaProvider(),
    ),
    private val settingsProvider: () -> QuotaSettingsState? = {
        runCatching { QuotaSettingsState.getInstance() }.getOrNull()
    },
    private val scheduler: ScheduledExecutorService = AppExecutorUtil.getAppScheduledExecutorService(),
    private val updatePublisher: (OpenAiCodexQuota?, String?, OpenCodeQuota?, String?, OllamaQuota?, String?, ZaiQuota?, String?, MiniMaxQuota?, String?, KimiQuota?, String?, CursorQuota?, String?) -> Unit = { quota, error, openCodeQuota, openCodeError, ollamaQuota, ollamaError, zaiQuota, zaiError, miniMaxQuota, miniMaxError, kimiQuota, kimiError, cursorQuota, cursorError ->
        ApplicationManager.getApplication().invokeLater {
            val publisher = ApplicationManager.getApplication().messageBus
                .syncPublisher(QuotaUsageListener.TOPIC)
            publisher.onQuotaUpdated(quota, error)
            publisher.onOpenCodeQuotaUpdated(openCodeQuota, openCodeError)
            publisher.onOllamaQuotaUpdated(ollamaQuota, ollamaError)
            publisher.onZaiQuotaUpdated(zaiQuota, zaiError)
            publisher.onMiniMaxQuotaUpdated(miniMaxQuota, miniMaxError)
            publisher.onKimiQuotaUpdated(kimiQuota, kimiError)
            publisher.onCursorQuotaUpdated(cursorQuota, cursorError)
            ActivityTracker.getInstance().inc()
        }
    },
    scheduleOnInit: Boolean = true,
) : Disposable {
    private val refreshingOpenAi = AtomicBoolean(false)
    private val refreshingOpenCode = AtomicBoolean(false)
    private val refreshingOllama = AtomicBoolean(false)
    private val refreshingZai = AtomicBoolean(false)
    private val refreshingMiniMax = AtomicBoolean(false)
    private val refreshingKimi = AtomicBoolean(false)
    private val refreshingCursor = AtomicBoolean(false)
    private val openAiProvider = providers.filterIsInstance<OpenAiQuotaProvider>().firstOrNull()
    private val openCodeProvider = providers.filterIsInstance<OpenCodeQuotaProvider>().firstOrNull()
    private val ollamaProvider = providers.filterIsInstance<OllamaQuotaProvider>().firstOrNull()
    private val zaiProvider = providers.filterIsInstance<ZaiQuotaProvider>().firstOrNull()
    private val miniMaxProvider = providers.filterIsInstance<MiniMaxQuotaProvider>().firstOrNull()
    private val kimiProvider = providers.filterIsInstance<KimiQuotaProvider>().firstOrNull()
    private val cursorProvider = providers.filterIsInstance<CursorQuotaProvider>().firstOrNull()
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
    fun getLastOpenCodeResponseJson(): String? = openCodeProvider?.getLastRawJson() ?: openCodeProvider?.getLastQuota()?.rawJson

    fun getLastOllamaQuota(): OllamaQuota? = ollamaProvider?.getLastQuota()
    fun getLastOllamaError(): String? = ollamaProvider?.getLastError()
    fun getLastOllamaResponseJson(): String? = ollamaProvider?.getLastRawJson()

    fun getLastZaiQuota(): ZaiQuota? = zaiProvider?.getLastQuota()
    fun getLastZaiError(): String? = zaiProvider?.getLastError()
    fun getLastZaiResponseJson(): String? = zaiProvider?.getLastRawJson()

    fun getLastMiniMaxQuota(): MiniMaxQuota? = miniMaxProvider?.getLastQuota()
    fun getLastMiniMaxError(): String? = miniMaxProvider?.getLastError()
    fun getLastMiniMaxResponseJson(): String? = miniMaxProvider?.getLastRawJson()

    fun getLastKimiQuota(): KimiQuota? = kimiProvider?.getLastQuota()
    fun getLastKimiError(): String? = kimiProvider?.getLastError()
    fun getLastKimiResponseJson(): String? = kimiProvider?.getLastRawJson()

    fun getLastCursorQuota(): CursorQuota? = cursorProvider?.getLastQuota()
    fun getLastCursorError(): String? = cursorProvider?.getLastError()
    fun getLastCursorResponseJson(): String? = cursorProvider?.getLastRawJson()

    internal fun getEffectiveIndicatorData(): QuotaIndicatorData {
        val settings = settingsProvider()
        val source = when (settings?.source() ?: QuotaIndicatorSource.OPEN_AI) {
            QuotaIndicatorSource.LAST_USED -> resolveLastActiveSource(settings)
            QuotaIndicatorSource.OPEN_AI -> QuotaIndicatorSource.OPEN_AI
            QuotaIndicatorSource.OPEN_CODE -> QuotaIndicatorSource.OPEN_CODE
            QuotaIndicatorSource.OLLAMA -> QuotaIndicatorSource.OLLAMA
            QuotaIndicatorSource.ZAI -> QuotaIndicatorSource.ZAI
            QuotaIndicatorSource.MINIMAX -> QuotaIndicatorSource.MINIMAX
            QuotaIndicatorSource.KIMI -> QuotaIndicatorSource.KIMI
            QuotaIndicatorSource.CURSOR -> QuotaIndicatorSource.CURSOR
        }

        return when (source) {
            QuotaIndicatorSource.OPEN_AI -> QuotaIndicatorData.OpenAi(getLastQuota(), getLastError())
            QuotaIndicatorSource.OPEN_CODE -> QuotaIndicatorData.OpenCode(getLastOpenCodeQuota(), getLastOpenCodeError())
            QuotaIndicatorSource.OLLAMA -> QuotaIndicatorData.Ollama(getLastOllamaQuota(), getLastOllamaError())
            QuotaIndicatorSource.ZAI -> QuotaIndicatorData.Zai(getLastZaiQuota(), getLastZaiError())
            QuotaIndicatorSource.MINIMAX -> QuotaIndicatorData.MiniMax(getLastMiniMaxQuota(), getLastMiniMaxError())
            QuotaIndicatorSource.KIMI -> QuotaIndicatorData.Kimi(getLastKimiQuota(), getLastKimiError())
            QuotaIndicatorSource.CURSOR -> QuotaIndicatorData.Cursor(getLastCursorQuota(), getLastCursorError())
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

    fun refreshZaiAsync() {
        AppExecutorUtil.getAppExecutorService().execute(::refreshZai)
    }

    fun refreshMiniMaxAsync() {
        AppExecutorUtil.getAppExecutorService().execute(::refreshMiniMax)
    }

    fun refreshKimiAsync() {
        AppExecutorUtil.getAppExecutorService().execute(::refreshKimi)
    }

    fun refreshCursorAsync() {
        AppExecutorUtil.getAppExecutorService().execute(::refreshCursor)
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

    fun refreshZaiBlocking() {
        refreshZai()
    }

    fun refreshMiniMaxBlocking() {
        refreshMiniMax()
    }

    fun refreshKimiBlocking() {
        refreshKimi()
    }

    fun refreshCursorBlocking() {
        refreshCursor()
    }

    fun clearUsageData(error: String? = null) {
        clearCodexUsageData(error)
        clearOpenCodeUsageData()
        clearOllamaUsageData()
        clearZaiUsageData()
        clearMiniMaxUsageData()
        clearKimiUsageData()
        clearCursorUsageData()
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

    fun clearZaiUsageData(error: String? = "No Z.ai API key configured") {
        zaiProvider?.clearData(error)
        settingsProvider()?.cachedZaiQuotaJson = null
        publishUpdate()
    }

    fun clearMiniMaxUsageData(error: String? = "MiniMax API key missing. Add a MiniMax API key in settings.") {
        miniMaxProvider?.clearData(error)
        settingsProvider()?.cachedMiniMaxQuotaJson = null
        publishUpdate()
    }

    fun clearKimiUsageData(error: String? = "Kimi login required. Log in from settings.") {
        kimiProvider?.clearData(error)
        settingsProvider()?.cachedKimiQuotaJson = null
        publishUpdate()
    }

    fun clearCursorUsageData(error: String? = "No Cursor session cookie configured. Paste WorkosCursorSessionToken from cursor.com in settings.") {
        cursorProvider?.clearData(error)
        settingsProvider()?.cachedCursorQuotaJson = null
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
        zaiProvider?.hydrateFromCache(settings)
        miniMaxProvider?.hydrateFromCache(settings)
        kimiProvider?.hydrateFromCache(settings)
        cursorProvider?.hydrateFromCache(settings)
    }

    private fun refreshNow() {
        val refreshes = listOf(
            ::refreshOpenAi,
            ::refreshOpenCode,
            ::refreshOllama,
            ::refreshZai,
            ::refreshMiniMax,
            ::refreshKimi,
            ::refreshCursor,
        )
        val executor = AppExecutorUtil.getAppExecutorService()
        val futures = refreshes.map { refresh ->
            executor.submit(refresh)
        }
        futures.forEach { it.get() }
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

    private fun refreshZai() {
        refreshProvider(zaiProvider, refreshingZai)
    }

    private fun refreshMiniMax() {
        refreshProvider(miniMaxProvider, refreshingMiniMax)
    }

    private fun refreshKimi() {
        refreshProvider(kimiProvider, refreshingKimi)
    }

    private fun refreshCursor() {
        refreshProvider(cursorProvider, refreshingCursor)
    }

    private fun refreshProvider(provider: QuotaProvider?, refreshing: AtomicBoolean) {
        if (provider == null || !refreshing.compareAndSet(false, true)) {
            return
        }

        try {
            val settings = settingsProvider()
            val oldFraction = getCachedUsageFraction(provider, settings)
            provider.refresh()
            val newFraction = getCurrentUsageFraction(provider)

            val significantChange = oldFraction != null && newFraction != null &&
                kotlin.math.abs(newFraction - oldFraction) >= MIN_USAGE_INCREASE

            if (significantChange && newFraction > oldFraction) {
                (settings ?: settingsProvider())?.lastActiveSource = provider.type.id
            }

            if (oldFraction == null || significantChange) {
                settings?.let(provider::persistToCache)
            }
            publishUpdate()
        } finally {
            refreshing.set(false)
        }
    }

    private fun getCachedUsageFraction(provider: QuotaProvider, settings: QuotaSettingsState?): Double? {
        return when (provider.type) {
            QuotaProviderType.OPEN_AI -> settings?.cachedOpenAiQuotaJson
                ?.let(QuotaSnapshotCache::decodeOpenAiQuota)
                ?.let(::extractOpenAiFraction)
            QuotaProviderType.OPEN_CODE -> settings?.cachedOpenCodeQuotaJson
                ?.let(QuotaSnapshotCache::decodeOpenCodeQuota)
                ?.let(::extractOpenCodeFraction)
            QuotaProviderType.OLLAMA -> settings?.cachedOllamaQuotaJson
                ?.let(QuotaSnapshotCache::decodeOllamaQuota)
                ?.let(::extractOllamaFraction)
            QuotaProviderType.ZAI -> settings?.cachedZaiQuotaJson
                ?.let(QuotaSnapshotCache::decodeZaiQuota)
                ?.let(::extractZaiFraction)
            QuotaProviderType.MINIMAX -> settings?.cachedMiniMaxQuotaJson
                ?.let(QuotaSnapshotCache::decodeMiniMaxQuota)
                ?.let(::extractMiniMaxFraction)
            QuotaProviderType.KIMI -> settings?.cachedKimiQuotaJson
                ?.let(QuotaSnapshotCache::decodeKimiQuota)
                ?.let(::extractKimiFraction)
            QuotaProviderType.CURSOR -> settings?.cachedCursorQuotaJson
                ?.let(QuotaSnapshotCache::decodeCursorQuota)
                ?.let(::extractCursorFraction)
        }
    }

    private fun getCurrentUsageFraction(provider: QuotaProvider): Double? {
        return when (provider) {
            is OpenAiQuotaProvider -> provider.getLastQuota()?.let(::extractOpenAiFraction)
            is OpenCodeQuotaProvider -> provider.getLastQuota()?.let(::extractOpenCodeFraction)
            is OllamaQuotaProvider -> provider.getLastQuota()?.let(::extractOllamaFraction)
            is ZaiQuotaProvider -> provider.getLastQuota()?.let(::extractZaiFraction)
            is MiniMaxQuotaProvider -> provider.getLastQuota()?.let(::extractMiniMaxFraction)
            is KimiQuotaProvider -> provider.getLastQuota()?.let(::extractKimiFraction)
            is CursorQuotaProvider -> provider.getLastQuota()?.let(::extractCursorFraction)
            else -> null
        }
    }

    private fun extractOpenAiFraction(quota: OpenAiCodexQuota): Double? {
        val windows = listOfNotNull(
            quota.primary?.usedPercent, quota.secondary?.usedPercent,
            quota.reviewPrimary?.usedPercent, quota.reviewSecondary?.usedPercent,
        )
        return windows.maxOrNull()?.let { it / 100.0 }
    }

    private fun extractOpenCodeFraction(quota: OpenCodeQuota): Double? {
        val windows = listOfNotNull(
            quota.rollingUsage?.usagePercent?.toDouble(),
            quota.weeklyUsage?.usagePercent?.toDouble(),
            quota.monthlyUsage?.usagePercent?.toDouble(),
        )
        return windows.maxOrNull()?.let { it / 100.0 }
    }

    private fun extractOllamaFraction(quota: OllamaQuota): Double? {
        val windows = listOfNotNull(quota.sessionUsage?.usagePercent, quota.weeklyUsage?.usagePercent)
        return windows.maxOrNull()?.let { it / 100.0 }
    }

    private fun extractZaiFraction(quota: ZaiQuota): Double? {
        val windows = listOfNotNull(
            quota.sessionUsage?.usagePercent,
            quota.weeklyUsage?.usagePercent,
            quota.webSearchUsage?.usagePercent,
        )
        return windows.maxOrNull()?.let { it / 100.0 }
    }

    private fun extractMiniMaxFraction(quota: MiniMaxQuota): Double? {
        return quota.sessionUsage?.usagePercent?.let { it / 100.0 }
    }

    private fun extractKimiFraction(quota: KimiQuota): Double? {
        val windows = listOfNotNull(quota.sessionUsage?.usagePercent, quota.totalUsage?.usagePercent)
        return windows.maxOrNull()?.let { it / 100.0 }
    }

    private fun extractCursorFraction(quota: CursorQuota): Double? {
        return quota.primaryUsagePercent()?.let { it / 100.0 }
    }

    private fun resolveLastActiveSource(settings: QuotaSettingsState?): QuotaIndicatorSource {
        val active = settings?.lastActiveSource
        if (!active.isNullOrBlank()) {
            return QuotaIndicatorSource.fromStorageValue(active)
        }
        return settings?.lastUsedSource() ?: QuotaIndicatorSource.OPEN_AI
    }

    private fun publishUpdate() {
        updatePublisher(
            getLastQuota(), getLastError(),
            getLastOpenCodeQuota(), getLastOpenCodeError(),
            getLastOllamaQuota(), getLastOllamaError(),
            getLastZaiQuota(), getLastZaiError(),
            getLastMiniMaxQuota(), getLastMiniMaxError(),
            getLastKimiQuota(), getLastKimiError(),
            getLastCursorQuota(), getLastCursorError(),
        )
    }

    override fun dispose() {
        scheduled?.cancel(true)
        scheduled = null
    }

    companion object {
        private const val MIN_USAGE_INCREASE = 0.005

        @JvmStatic
        fun getInstance(): QuotaUsageService {
            return ApplicationManager.getApplication().getService(QuotaUsageService::class.java)
        }
    }
}
