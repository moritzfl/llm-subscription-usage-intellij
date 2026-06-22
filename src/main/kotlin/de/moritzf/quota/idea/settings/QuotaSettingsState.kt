package de.moritzf.quota.idea.settings

import com.intellij.openapi.application.ApplicationManager
import de.moritzf.quota.idea.common.QuotaProviderRegistry
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaSnapshotCache
import de.moritzf.quota.idea.mcp.McpServerSyncTarget
import de.moritzf.quota.idea.mcp.McpServerTransport
import de.moritzf.quota.idea.openai.OpenAiProxyService
import de.moritzf.quota.idea.ui.indicator.QuotaIndicatorLocation
import de.moritzf.quota.idea.ui.indicator.QuotaIndicatorSource
import de.moritzf.quota.minimax.MiniMaxRegionPreference
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Persistent plugin settings shared at application scope.
 *
 * Per-provider values (cache, timestamps, popup visibility) are stored in maps
 * keyed by [QuotaProviderType.id]; new providers need no changes here.
 */
@State(name = "OpenAiUsageQuotaSettings", storages = [Storage("openai-usage-quota.xml")])
@Service(Service.Level.APP)
class QuotaSettingsState : PersistentStateComponent<QuotaSettingsState> {
    var refreshMinutes: Int = 5
    var statusBarDisplayMode: String = QuotaDisplayMode.ICON_ONLY.name
    var indicatorLocation: String = QuotaIndicatorLocation.STATUS_BAR.name
    var indicatorSource: String = QuotaIndicatorSource.OPEN_AI.name
    var lastProviderUpdates: MutableMap<String, Long> = mutableMapOf()
    var hiddenFromQuotaPopup: MutableList<String> = mutableListOf()
    var cachedQuotaJsons: MutableMap<String, String> = mutableMapOf()
    var lastActiveSource: String? = null
    var openCodeWorkspaceId: String? = null
    var minimaxRegionPreference: String = MiniMaxRegionPreference.AUTO.name
    var providerOrder: String = DEFAULT_PROVIDER_ORDER
    var syncIntellijMcpServerUrl: Boolean = false
    var mcpServerSyncTargets: MutableList<McpServerSyncTarget> = mutableListOf()
    var openAiProxyEnabled: Boolean = false
    var openAiProxyPort: Int = OpenAiProxyService.DEFAULT_PORT
    var openAiProxyLogRequests: Boolean = false
    var subscriptionProxyEnabledProviders: MutableList<String> = DEFAULT_SUBSCRIPTION_PROXY_PROVIDERS.toMutableList()
    var subscriptionProxyModelCatalogJsons: MutableMap<String, String> = mutableMapOf()
    var githubEnterpriseHost: String = ""

    // Legacy per-provider fields kept only so settings written by older
    // versions still deserialize; loadState migrates them into the maps.
    @Deprecated("Migrated into lastProviderUpdates")
    var lastOpenAiUpdate: Long = 0
    @Deprecated("Migrated into lastProviderUpdates")
    var lastOpenCodeUpdate: Long = 0
    @Deprecated("Migrated into lastProviderUpdates")
    var lastOllamaUpdate: Long = 0
    @Deprecated("Migrated into lastProviderUpdates")
    var lastZaiUpdate: Long = 0
    @Deprecated("Migrated into lastProviderUpdates")
    var lastMiniMaxUpdate: Long = 0
    @Deprecated("Migrated into lastProviderUpdates")
    var lastKimiUpdate: Long = 0
    @Deprecated("Migrated into lastProviderUpdates")
    var lastGitHubUpdate: Long = 0
    @Deprecated("Migrated into lastProviderUpdates")
    var lastCursorUpdate: Long = 0
    @Deprecated("Migrated into hiddenFromQuotaPopup")
    var hideOpenAiFromQuotaPopup: Boolean = false
    @Deprecated("Migrated into hiddenFromQuotaPopup")
    var hideOpenCodeFromQuotaPopup: Boolean = false
    @Deprecated("Migrated into hiddenFromQuotaPopup")
    var hideOllamaFromQuotaPopup: Boolean = false
    @Deprecated("Migrated into hiddenFromQuotaPopup")
    var hideZaiFromQuotaPopup: Boolean = false
    @Deprecated("Migrated into hiddenFromQuotaPopup")
    var hideMiniMaxFromQuotaPopup: Boolean = false
    @Deprecated("Migrated into hiddenFromQuotaPopup")
    var hideKimiFromQuotaPopup: Boolean = false
    @Deprecated("Migrated into hiddenFromQuotaPopup")
    var hideGitHubFromQuotaPopup: Boolean = false
    @Deprecated("Migrated into hiddenFromQuotaPopup")
    var hideCursorFromQuotaPopup: Boolean = false
    @Deprecated("Migrated into cachedQuotaJsons")
    var cachedOpenAiQuotaJson: String? = null
    @Deprecated("Migrated into cachedQuotaJsons")
    var cachedOpenCodeQuotaJson: String? = null
    @Deprecated("Migrated into cachedQuotaJsons")
    var cachedOllamaQuotaJson: String? = null
    @Deprecated("Migrated into cachedQuotaJsons")
    var cachedZaiQuotaJson: String? = null
    @Deprecated("Migrated into cachedQuotaJsons")
    var cachedMiniMaxQuotaJson: String? = null
    @Deprecated("Migrated into cachedQuotaJsons")
    var cachedKimiQuotaJson: String? = null
    @Deprecated("Migrated into cachedQuotaJsons")
    var cachedGitHubQuotaJson: String? = null
    @Deprecated("Migrated into cachedQuotaJsons")
    var cachedCursorQuotaJson: String? = null

    override fun getState(): QuotaSettingsState = this

    override fun loadState(state: QuotaSettingsState) {
        refreshMinutes = state.refreshMinutes
        statusBarDisplayMode = QuotaDisplayMode.fromStorageValue(state.statusBarDisplayMode).name
        indicatorLocation = QuotaIndicatorLocation.fromStorageValue(state.indicatorLocation).name
        indicatorSource = state.indicatorSource
        lastProviderUpdates = migrateTimestamps(state)
        hiddenFromQuotaPopup = migrateHiddenProviders(state)
        cachedQuotaJsons = migrateCachedJsons(state)
        lastActiveSource = state.lastActiveSource
        openCodeWorkspaceId = state.openCodeWorkspaceId
        minimaxRegionPreference = MiniMaxRegionPreference.fromStorageValue(state.minimaxRegionPreference).name
        providerOrder = state.providerOrder.ifBlank { DEFAULT_PROVIDER_ORDER }
        syncIntellijMcpServerUrl = state.syncIntellijMcpServerUrl
        mcpServerSyncTargets = state.mcpServerSyncTargets.map { target ->
            target.copy(
                transportType = McpServerTransport.fromStorageValue(target.transportType).name,
            )
        }.toMutableList()
        openAiProxyEnabled = state.openAiProxyEnabled
        openAiProxyPort = OpenAiProxyService.sanitizePort(state.openAiProxyPort.takeIf { it > 0 } ?: OpenAiProxyService.DEFAULT_PORT)
        openAiProxyLogRequests = state.openAiProxyLogRequests
        subscriptionProxyEnabledProviders = sanitizeSubscriptionProxyProviders(state.subscriptionProxyEnabledProviders).toMutableList()
        subscriptionProxyModelCatalogJsons = state.subscriptionProxyModelCatalogJsons.toMutableMap()
        githubEnterpriseHost = state.githubEnterpriseHost.trim()
    }

    @Suppress("DEPRECATION")
    private fun migrateTimestamps(state: QuotaSettingsState): MutableMap<String, Long> {
        val result = state.lastProviderUpdates.toMutableMap()
        fun migrate(type: QuotaProviderType, legacy: Long) {
            if (legacy > 0 && type.id !in result) result[type.id] = legacy
        }
        migrate(QuotaProviderType.OPEN_AI, state.lastOpenAiUpdate)
        migrate(QuotaProviderType.OPEN_CODE, state.lastOpenCodeUpdate)
        migrate(QuotaProviderType.OLLAMA, state.lastOllamaUpdate)
        migrate(QuotaProviderType.ZAI, state.lastZaiUpdate)
        migrate(QuotaProviderType.MINIMAX, state.lastMiniMaxUpdate)
        migrate(QuotaProviderType.KIMI, state.lastKimiUpdate)
        migrate(QuotaProviderType.GITHUB, state.lastGitHubUpdate)
        migrate(QuotaProviderType.CURSOR, state.lastCursorUpdate)
        return result
    }

    @Suppress("DEPRECATION")
    private fun migrateHiddenProviders(state: QuotaSettingsState): MutableList<String> {
        val result = state.hiddenFromQuotaPopup.toMutableList()
        fun migrate(type: QuotaProviderType, legacy: Boolean) {
            if (legacy && type.id !in result) result.add(type.id)
        }
        migrate(QuotaProviderType.OPEN_AI, state.hideOpenAiFromQuotaPopup)
        migrate(QuotaProviderType.OPEN_CODE, state.hideOpenCodeFromQuotaPopup)
        migrate(QuotaProviderType.OLLAMA, state.hideOllamaFromQuotaPopup)
        migrate(QuotaProviderType.ZAI, state.hideZaiFromQuotaPopup)
        migrate(QuotaProviderType.MINIMAX, state.hideMiniMaxFromQuotaPopup)
        migrate(QuotaProviderType.KIMI, state.hideKimiFromQuotaPopup)
        migrate(QuotaProviderType.GITHUB, state.hideGitHubFromQuotaPopup)
        migrate(QuotaProviderType.CURSOR, state.hideCursorFromQuotaPopup)
        return result
    }

    @Suppress("DEPRECATION")
    private fun migrateCachedJsons(state: QuotaSettingsState): MutableMap<String, String> {
        val result = state.cachedQuotaJsons.toMutableMap()
        fun migrate(type: QuotaProviderType, legacy: String?) {
            if (!legacy.isNullOrBlank() && type.id !in result) result[type.id] = legacy
        }
        migrate(QuotaProviderType.OPEN_AI, state.cachedOpenAiQuotaJson)
        migrate(QuotaProviderType.OPEN_CODE, state.cachedOpenCodeQuotaJson)
        migrate(QuotaProviderType.OLLAMA, state.cachedOllamaQuotaJson)
        migrate(QuotaProviderType.ZAI, state.cachedZaiQuotaJson)
        migrate(QuotaProviderType.MINIMAX, state.cachedMiniMaxQuotaJson)
        migrate(QuotaProviderType.KIMI, state.cachedKimiQuotaJson)
        migrate(QuotaProviderType.GITHUB, state.cachedGitHubQuotaJson)
        migrate(QuotaProviderType.CURSOR, state.cachedCursorQuotaJson)
        return result
    }

    fun providerOrderList(): List<QuotaProviderType> {
        val stored = providerOrder.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { QuotaProviderType.fromId(it) }
        return QuotaProviderRegistry.mergeProviderOrder(stored)
    }

    fun displayMode(): QuotaDisplayMode = QuotaDisplayMode.fromStorageValue(statusBarDisplayMode)

    fun setDisplayMode(displayMode: QuotaDisplayMode) {
        statusBarDisplayMode = displayMode.name
    }

    fun location(): QuotaIndicatorLocation = QuotaIndicatorLocation.fromStorageValue(indicatorLocation)

    fun setLocation(location: QuotaIndicatorLocation) {
        indicatorLocation = location.name
    }

    fun source(): QuotaIndicatorSource = QuotaIndicatorSource.fromStorageValue(indicatorSource)

    fun miniMaxRegionPreference(): MiniMaxRegionPreference =
        MiniMaxRegionPreference.fromStorageValue(minimaxRegionPreference)

    fun setSource(source: QuotaIndicatorSource) {
        indicatorSource = source.name
    }

    fun isHiddenFromPopup(provider: QuotaProviderType): Boolean = provider.id in hiddenFromQuotaPopup

    fun setHiddenFromPopup(provider: QuotaProviderType, hidden: Boolean) {
        if (hidden) {
            if (provider.id !in hiddenFromQuotaPopup) hiddenFromQuotaPopup.add(provider.id)
        } else {
            hiddenFromQuotaPopup.remove(provider.id)
        }
    }

    fun cachedQuotaJson(provider: QuotaProviderType): String? = cachedQuotaJsons[provider.id]

    fun setCachedQuotaJson(provider: QuotaProviderType, json: String?) {
        if (json == null) {
            cachedQuotaJsons.remove(provider.id)
        } else {
            cachedQuotaJsons[provider.id] = json
        }
    }

    fun lastUpdate(provider: QuotaProviderType): Long = lastProviderUpdates[provider.id] ?: 0L

    fun updateTimestamp(provider: QuotaProviderType) {
        lastProviderUpdates[provider.id] = System.currentTimeMillis()
    }

    fun isSubscriptionProxyProviderEnabled(provider: QuotaProviderType): Boolean {
        return provider.id in subscriptionProxyEnabledProviders
    }

    fun setSubscriptionProxyProviderEnabled(provider: QuotaProviderType, enabled: Boolean) {
        if (provider !in SUBSCRIPTION_PROXY_SUPPORTED_PROVIDERS) return
        if (enabled) {
            if (provider.id !in subscriptionProxyEnabledProviders) subscriptionProxyEnabledProviders.add(provider.id)
        } else {
            subscriptionProxyEnabledProviders.remove(provider.id)
        }
        subscriptionProxyEnabledProviders = sanitizeSubscriptionProxyProviders(subscriptionProxyEnabledProviders).toMutableList()
    }

    fun enabledSubscriptionProxyProviders(): Set<QuotaProviderType> {
        return sanitizeSubscriptionProxyProviders(subscriptionProxyEnabledProviders)
            .mapNotNull(QuotaProviderType::fromId)
            .toSet()
    }

    fun subscriptionProxyModelCatalogJson(providerId: String): String? = subscriptionProxyModelCatalogJsons[providerId]

    fun setSubscriptionProxyModelCatalogJson(providerId: String, json: String?) {
        if (json.isNullOrBlank()) {
            subscriptionProxyModelCatalogJsons.remove(providerId)
        } else {
            subscriptionProxyModelCatalogJsons[providerId] = json
        }
    }

    fun lastUsedSource(): QuotaIndicatorSource {
        val updates = QuotaProviderRegistry.all.associate { registration ->
            val provider = registration.type
            provider to (lastUpdate(provider).takeIf { it > 0 }
                ?: QuotaSnapshotCache.decode(provider, cachedQuotaJson(provider))?.fetchedAt?.toEpochMilliseconds()
                ?: 0L)
        }
        if (updates.values.max() == 0L) return QuotaIndicatorSource.OPEN_AI
        val latest = QuotaProviderRegistry.defaultProviderOrder().maxByOrNull { updates.getValue(it) }
        return QuotaIndicatorSource.entries.firstOrNull { it.providerType == latest }
            ?: QuotaIndicatorSource.OPEN_AI
    }

    companion object {
        val SUBSCRIPTION_PROXY_SUPPORTED_PROVIDERS: List<QuotaProviderType> = listOf(
            QuotaProviderType.OPEN_AI,
            QuotaProviderType.SUPERGROK,
            QuotaProviderType.GITHUB,
            QuotaProviderType.KIMI,
            QuotaProviderType.MINIMAX,
            QuotaProviderType.OLLAMA,
            QuotaProviderType.OPEN_CODE,
            QuotaProviderType.ZAI,
        )

        val DEFAULT_SUBSCRIPTION_PROXY_PROVIDERS: List<String> =
            SUBSCRIPTION_PROXY_SUPPORTED_PROVIDERS.map { it.id }

        val DEFAULT_PROVIDER_ORDER: String
            get() = QuotaProviderRegistry.defaultProviderOrderStorageValue()

        fun sanitizeSubscriptionProxyProviders(ids: List<String>?): List<String> {
            val supportedIds = SUBSCRIPTION_PROXY_SUPPORTED_PROVIDERS.map { it.id }
            return ids.orEmpty()
                .map { it.trim() }
                .filter { it in supportedIds }
                .distinct()
        }

        @JvmStatic
        fun getInstance(): QuotaSettingsState {
            return ApplicationManager.getApplication().getService(QuotaSettingsState::class.java)
        }
    }
}
