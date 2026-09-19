package de.moritzf.quota.idea.common

import com.intellij.concurrency.virtualThreads.IntelliJVirtualThreads
import com.intellij.ide.ActivityTracker
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import de.moritzf.quota.idea.settings.AccountResolver
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.idea.ui.indicator.QuotaIndicatorData
import de.moritzf.quota.idea.ui.indicator.QuotaIndicatorSource
import de.moritzf.quota.shared.ProviderQuota
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Periodically fetches quota data from all registered providers and publishes updates to the IDE message bus.
 */
@Service(Service.Level.APP)
class QuotaUsageService(
    providers: List<QuotaProvider> = defaultProviders(),
    private val settingsProvider: () -> QuotaSettingsState? = {
        runCatching { QuotaSettingsState.getInstance() }.getOrNull()
    },
    private val scheduler: ScheduledExecutorService = AppExecutorUtil.getAppScheduledExecutorService(),
    private val updatePublisher: (QuotaUsageSnapshot) -> Unit = { snapshot ->
        ApplicationManager.getApplication().invokeLater {
            val publisher = ApplicationManager.getApplication().messageBus
                .syncPublisher(QuotaUsageListener.TOPIC)
            snapshot.accountEntries.forEach { (accountId, entry) ->
                val type = snapshot.accountTypes[accountId] ?: return@forEach
                publisher.onQuotaUpdated(type, entry.quota, entry.error, accountId)
            }
            ActivityTracker.getInstance().inc()
        }
    },
    scheduleOnInit: Boolean = true,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) : Disposable {
    private class ProviderState(val provider: QuotaProvider) {
        @Volatile var inFlight: CompletableFuture<Unit>? = null
        val lock = Any()
    }

    private val states = ConcurrentHashMap<String, ProviderState>().apply {
        providers.forEach { provider -> put(provider.accountId, ProviderState(provider)) }
    }
    /**
     * Sticky per-account, per-window activity baselines for "Last used" detection.
     * Only move a window baseline on significant increase (marks last-used) or
     * significant decrease (reset/decay). Sub-threshold growth must NOT move the
     * baseline, otherwise slow usage never accumulates past [MIN_USAGE_INCREASE]
     * between polls. Windows are compared independently so decay in one limit
     * cannot cancel growth in another.
     */
    private val activityBaselines = ConcurrentHashMap<String, Map<String, Double>>()
    private var scheduled: ScheduledFuture<*>? = null

    init {
        hydrateCachedQuotas()
        if (scheduleOnInit) {
            scheduleRefresh()
        }
    }

    fun provider(type: QuotaProviderType): QuotaProvider? {
        val preferredId = settingsProvider()?.defaultAccount(type)?.id ?: type.id
        return states[preferredId]?.provider
    }

    fun providerForAccount(accountId: String): QuotaProvider? = states[accountId]?.provider

    fun getLastQuota(type: QuotaProviderType): ProviderQuota? = provider(type)?.getLastQuota()

    fun getLastQuota(accountId: String): ProviderQuota? = providerForAccount(accountId)?.getLastQuota()

    fun getLastError(type: QuotaProviderType): String? = provider(type)?.getLastError()

    fun getLastError(accountId: String): String? = providerForAccount(accountId)?.getLastError()

    fun getLastResponseJson(type: QuotaProviderType): String? = provider(type)?.getLastRawJson()

    fun getLastResponseJson(accountId: String): String? = providerForAccount(accountId)?.getLastRawJson()

    /**
     * The error the status bar and popup should show. A temporary failure is hidden while a
     * reading is available: the quota stays visible with its "Updated" time instead of being
     * replaced by a message that the next refresh resolves. The settings page keeps using
     * [getLastError], so the failure is still visible for diagnosis.
     */
    private fun displayError(provider: QuotaProvider): String? {
        val error = provider.getLastError() ?: return null
        if (provider.isLastErrorTransient() && provider.getLastQuota() != null) {
            return null
        }
        return error
    }

    fun currentSnapshot(): QuotaUsageSnapshot {
        val accountEntries = states.mapValues { (_, state) ->
            ProviderSnapshot(state.provider.getLastQuota(), displayError(state.provider))
        }
        val accountTypes = states.mapValues { (_, state) -> state.provider.type }
        val settings = settingsProvider()
        val typeEntries = QuotaProviderType.entries.mapNotNull { type ->
            val preferredId = settings?.defaultAccount(type)?.id
                ?: settings?.accountsOf(type)?.firstOrNull()?.id
                ?: type.id
            val state = states[preferredId] ?: return@mapNotNull null
            type to ProviderSnapshot(state.provider.getLastQuota(), displayError(state.provider))
        }.toMap()
        return QuotaUsageSnapshot(typeEntries, accountEntries, accountTypes)
    }

    internal fun getEffectiveIndicatorData(): QuotaIndicatorData {
        val settings = settingsProvider()
        val configured = settings?.source() ?: QuotaIndicatorSource.OPEN_AI
        val source = when (configured) {
            QuotaIndicatorSource.LAST_USED -> resolveLastActiveSource(settings)
            else -> configured
        }
        val type = source.providerType ?: QuotaProviderType.OPEN_AI
        val accountId = when (configured) {
            QuotaIndicatorSource.LAST_USED ->
                settings?.lastActiveAccount()?.id ?: settings?.defaultAccount(type)?.id ?: type.id
            else -> settings?.defaultAccount(type)?.id ?: type.id
        }
        val accountProvider = providerForAccount(accountId)
        return QuotaIndicatorData(
            type,
            accountProvider?.getLastQuota(),
            accountProvider?.let(::displayError),
            accountId,
        )
    }

    fun refreshNowAsync() {
        AppExecutorUtil.getAppExecutorService().execute(::refreshNow)
    }

    fun refreshNowBlocking() {
        refreshNow()
    }

    fun refreshAsync(type: QuotaProviderType) {
        AppExecutorUtil.getAppExecutorService().execute { refreshProvider(provider(type)?.accountId ?: type.id) }
    }

    fun refreshAsync(accountId: String) {
        AppExecutorUtil.getAppExecutorService().execute { refreshProvider(accountId) }
    }

    fun refreshBlocking(type: QuotaProviderType) {
        refreshProvider(provider(type)?.accountId ?: type.id)
    }

    fun refreshBlocking(accountId: String) {
        refreshProvider(accountId)
    }

    fun clearAllUsageData(openAiError: String? = null) {
        states.keys.toList().forEach { id ->
            val type = states[id]?.provider?.type
            clearUsageData(id, if (type == QuotaProviderType.OPEN_AI) openAiError else null)
        }
    }

    fun clearUsageData(type: QuotaProviderType, error: String? = null) {
        val ids = states.filter { it.value.provider.type == type }.keys
        if (ids.isEmpty()) {
            clearUsageData(type.id, error)
            return
        }
        ids.forEach { clearUsageData(it, error) }
    }

    fun clearUsageData(accountId: String, error: String? = null) {
        val provider = providerForAccount(accountId) ?: return
        provider.clearData(error ?: provider.notConfiguredMessage)
        activityBaselines.remove(accountId)
        settingsProvider()?.setCachedQuotaJson(accountId, null)
        publishUpdate()
    }

    fun syncAccounts() {
        val settings = settingsProvider() ?: return
        val accounts = settings.accounts
        if (accounts.isEmpty() && settings.settingsVersion >= 3) {
            states.clear()
            activityBaselines.clear()
            settings.pruneOrphanAccountData()
            publishUpdate()
            return
        }
        if (accounts.isEmpty()) {
            return
        }
        val desired = accounts.map { it.id }.toSet()
        val added = mutableListOf<String>()
        accounts.forEach { account ->
            if (states.containsKey(account.id)) {
                return@forEach
            }
            val type = account.providerType() ?: return@forEach
            val provider = ProviderCatalog.get(type).quotaFactory(account)
            provider.hydrateFromCache(settings)
            states[account.id] = ProviderState(provider)
            added += account.id
        }
        states.keys.filter { it !in desired }.forEach { id ->
            states.remove(id)
            activityBaselines.remove(id)
        }
        publishUpdate()
        added.forEach(::refreshAsync)
    }

    fun resetOpenCodeWorkspaceCache(accountId: String = QuotaProviderType.OPEN_CODE.id) {
        (providerForAccount(accountId) as? OpenCodeQuotaProvider)?.resetWorkspaceCache()
    }

    fun consumeOpenAiResetCredit(creditId: String?, accountId: String) {
        val provider = providerForAccount(accountId) as? OpenAiQuotaProvider ?: return
        provider.consumeResetCredit(creditId)
        refreshProvider(accountId, forceUpdate = true)
    }

    fun consumeSuperGrokReset(tokenId: String?, accountId: String) {
        val provider = providerForAccount(accountId) as? SuperGrokQuotaProvider ?: return
        provider.consumeReset(tokenId)
        sleeper(SUPERGROK_RESET_PROPAGATION_MS)
        refreshProvider(accountId, forceUpdate = true)
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
        val futures = states.keys.map { accountId ->
            CompletableFuture.runAsync(
                {
                    runCatching { refreshProvider(accountId) }
                        .onFailure { LOG.warn("Quota provider refresh failed", it) }
                },
                { command -> IntelliJVirtualThreads.ofVirtual().start(command) },
            )
        }
        futures.forEach { future ->
            runCatching { future.join() }
        }
    }

    private fun refreshProvider(accountId: String, forceUpdate: Boolean = false) {
        while (true) {
            val state = states[accountId] ?: return
            val ownedFuture: CompletableFuture<Unit>?
            val waitFuture: CompletableFuture<Unit>?
            synchronized(state.lock) {
                val existing = state.inFlight
                if (existing != null) {
                    ownedFuture = null
                    waitFuture = existing
                } else {
                    val created = CompletableFuture<Unit>()
                    state.inFlight = created
                    ownedFuture = created
                    waitFuture = null
                }
            }
            if (waitFuture != null) {
                runCatching { waitFuture.get() }
                if (!forceUpdate) return
                continue
            }
            val future = ownedFuture ?: return

            try {
                val provider = state.provider
                val settings = settingsProvider()
                if (forceUpdate) {
                    when (provider) {
                        is SuperGrokQuotaProvider -> provider.refresh(forceUpdate = true)
                        is OpenAiQuotaProvider -> provider.refresh(forceUpdate = true)
                        else -> provider.refresh()
                    }
                } else {
                    provider.refresh()
                }
                noteActivity(accountId, provider, settings)
                val quota = provider.getLastQuota()
                if (quota != null) {
                    settings?.let(provider::persistToCache)
                    if (!AccountResolver.isHardStop(quota)) {
                        AccountResolver.clearRateLimited(accountId)
                    }
                }
                publishUpdate()
                future.complete(Unit)
            } catch (exception: Exception) {
                future.completeExceptionally(exception)
                throw exception
            } finally {
                synchronized(state.lock) {
                    if (state.inFlight === future) {
                        state.inFlight = null
                    }
                }
            }
            return
        }
    }

    private fun noteActivity(
        accountId: String,
        provider: QuotaProvider,
        settings: QuotaSettingsState?,
    ) {
        if (settings == null) return
        val current = provider.currentActivityWindows()
        if (current.isEmpty()) return

        val previous = activityBaselines[accountId]
            ?: provider.cachedActivityWindows(settings).takeIf { it.isNotEmpty() }
        if (previous == null) {
            activityBaselines[accountId] = current
            return
        }

        var sawIncrease = false
        val nextBaseline = LinkedHashMap<String, Double>(current.size)
        for ((name, newValue) in current) {
            val oldValue = previous[name]
            if (oldValue == null) {
                // New window appeared — seed only; a mid-life appearance at a high
                // value is not proof of a fresh request from this poll cycle.
                nextBaseline[name] = newValue
                continue
            }
            val delta = newValue - oldValue
            when {
                delta >= MIN_USAGE_INCREASE -> {
                    sawIncrease = true
                    nextBaseline[name] = newValue
                }
                delta <= -MIN_USAGE_INCREASE -> {
                    // Window reset or natural decay — drop baseline so later growth counts.
                    nextBaseline[name] = newValue
                }
                else -> {
                    // Sub-threshold change: keep old baseline so slow growth accumulates.
                    nextBaseline[name] = oldValue
                }
            }
        }
        // Drop baselines for windows that disappeared from the payload.
        activityBaselines[accountId] = nextBaseline
        if (sawIncrease) {
            settings.setLastActiveAccount(accountId)
        }
    }

    private fun resolveLastActiveSource(settings: QuotaSettingsState?): QuotaIndicatorSource {
        settings?.lastActiveProvider()?.let { return QuotaIndicatorSource.forProvider(it) }
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
        private const val SUPERGROK_RESET_PROPAGATION_MS = 2_000L

        @JvmStatic
        fun getInstance(): QuotaUsageService {
            return ApplicationManager.getApplication().getService(QuotaUsageService::class.java)
        }

        private fun defaultProviders(): List<QuotaProvider> {
            val settings = runCatching { QuotaSettingsState.getInstance() }.getOrNull()
            val accounts = settings?.accounts.orEmpty()
            if (accounts.isNotEmpty()) {
                return ProviderCatalog.createAccountProviders(accounts)
            }
            if (settings != null && settings.settingsVersion >= 3) {
                return emptyList()
            }
            return ProviderCatalog.createQuotaProviders()
        }
    }
}
