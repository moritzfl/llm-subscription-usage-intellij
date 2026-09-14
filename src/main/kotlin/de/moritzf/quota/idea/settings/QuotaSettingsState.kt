package de.moritzf.quota.idea.settings

import com.intellij.openapi.application.ApplicationManager
import de.moritzf.quota.idea.common.ProviderCatalog
import de.moritzf.quota.idea.common.QuotaProviderRegistry
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaSnapshotCache
import de.moritzf.quota.idea.mcp.McpServerSyncTarget
import de.moritzf.quota.idea.mcp.McpServerTransport
import de.moritzf.proxy.fim.CompletionsConfig
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
 * Per-account values (cache, timestamps) are stored in maps keyed by
 * [ProviderAccount.id]. [QuotaProviderType.id] remains the catalog / MCP type.
 */
@State(name = "OpenAiUsageQuotaSettings", storages = [Storage("openai-usage-quota.xml")])
@Service(Service.Level.APP)
class QuotaSettingsState : PersistentStateComponent<QuotaSettingsState> {
    var refreshMinutes: Int = 5
    var statusBarDisplayMode: String = QuotaDisplayMode.ICON_ONLY.name
    var indicatorLocation: String = QuotaIndicatorLocation.STATUS_BAR.name
    var indicatorSource: String = QuotaIndicatorSource.OPEN_AI.storageId
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
    var proxyCompletionsEnabled: Boolean = true
    var proxyCompletionsModelId: String = ""
    var proxyCompletionsUseChatAdapter: Boolean = true
    var proxyCompletionsMaxOutputTokens: Int = CompletionsConfig.DEFAULT_MAX_OUTPUT_TOKENS
    var proxyCompletionsMaxRequestsPerMinute: Int = CompletionsConfig.DEFAULT_MAX_REQUESTS_PER_MINUTE
    var proxyCompletionsTimeoutSeconds: Int = CompletionsConfig.DEFAULT_TIMEOUT_SECONDS
    var proxyCompletionsPriorityTier: Boolean = false
    var subscriptionProxyEnabledProviders: MutableList<String> = DEFAULT_SUBSCRIPTION_PROXY_PROVIDERS.toMutableList()
    var subscriptionProxyModelCatalogJsons: MutableMap<String, String> = mutableMapOf()
    var githubEnterpriseHost: String = ""
    var accounts: MutableList<ProviderAccount> = mutableListOf()

    /** Shape of the persisted data; raised by [QuotaSettingsMigrations] as migrations run. */
    var settingsVersion: Int = 0

    override fun getState(): QuotaSettingsState = this

    override fun loadState(state: QuotaSettingsState) {
        settingsVersion = state.settingsVersion
        refreshMinutes = state.refreshMinutes
        statusBarDisplayMode = QuotaDisplayMode.fromStorageValue(state.statusBarDisplayMode).name
        indicatorLocation = QuotaIndicatorLocation.fromStorageValue(state.indicatorLocation).name
        indicatorSource = state.indicatorSource
        lastProviderUpdates = state.lastProviderUpdates.toMutableMap()
        hiddenFromQuotaPopup = state.hiddenFromQuotaPopup.toMutableList()
        cachedQuotaJsons = state.cachedQuotaJsons.toMutableMap()
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
        proxyCompletionsEnabled = state.proxyCompletionsEnabled
        proxyCompletionsModelId = state.proxyCompletionsModelId.trim()
        proxyCompletionsUseChatAdapter = state.proxyCompletionsUseChatAdapter
        proxyCompletionsMaxOutputTokens = CompletionsConfig.clampMaxOutputTokens(state.proxyCompletionsMaxOutputTokens)
        proxyCompletionsMaxRequestsPerMinute =
            CompletionsConfig.clampMaxRequestsPerMinute(state.proxyCompletionsMaxRequestsPerMinute)
        proxyCompletionsTimeoutSeconds = CompletionsConfig.clampTimeoutSeconds(state.proxyCompletionsTimeoutSeconds)
        proxyCompletionsPriorityTier = state.proxyCompletionsPriorityTier
        subscriptionProxyEnabledProviders = sanitizeSubscriptionProxyProviders(state.subscriptionProxyEnabledProviders).toMutableList()
        subscriptionProxyModelCatalogJsons = state.subscriptionProxyModelCatalogJsons.toMutableMap()
        githubEnterpriseHost = state.githubEnterpriseHost.trim()
        accounts = sanitizeAccounts(state.accounts).toMutableList()
        QuotaSettingsMigrations.run(this)
        pruneOrphanAccountData()
    }

    /** Fresh install: nothing was ever persisted, so only record the current settings version. */
    override fun noStateLoaded() {
        settingsVersion = QuotaSettingsMigrations.CURRENT_VERSION
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
        indicatorSource = source.storageId
    }

    fun lastActiveAccount(): ProviderAccount? {
        val id = lastActiveSource ?: return null
        return account(id)
    }

    fun lastActiveProvider(): QuotaProviderType? =
        lastActiveAccount()?.providerType() ?: lastActiveSource?.let(QuotaProviderType::fromId)

    fun setLastActiveProvider(type: QuotaProviderType) {
        lastActiveSource = defaultAccount(type)?.id ?: type.id
    }

    fun setLastActiveAccount(accountId: String) {
        lastActiveSource = accountId
    }

    fun githubHostFor(accountId: String): String =
        account(accountId)?.extra(ProviderAccount.EXTRA_GITHUB_HOST) ?: githubEnterpriseHost

    fun setGithubHostFor(accountId: String, value: String) {
        val trimmed = value.trim()
        account(accountId)?.setExtra(ProviderAccount.EXTRA_GITHUB_HOST, trimmed.ifEmpty { null })
        if (accountId == QuotaProviderType.GITHUB.id) {
            githubEnterpriseHost = trimmed
        }
    }

    fun miniMaxRegionFor(accountId: String): MiniMaxRegionPreference =
        account(accountId)?.extra(ProviderAccount.EXTRA_MINIMAX_REGION)
            ?.let(MiniMaxRegionPreference::fromStorageValue)
            ?: miniMaxRegionPreference()

    fun setMiniMaxRegionFor(accountId: String, value: MiniMaxRegionPreference) {
        account(accountId)?.setExtra(ProviderAccount.EXTRA_MINIMAX_REGION, value.name)
        if (accountId == QuotaProviderType.MINIMAX.id) {
            minimaxRegionPreference = value.name
        }
    }

    fun openCodeWorkspaceIdFor(accountId: String): String? =
        account(accountId)?.extra(ProviderAccount.EXTRA_OPENCODE_WORKSPACE)
            ?: openCodeWorkspaceId.takeIf { accountId == QuotaProviderType.OPEN_CODE.id }

    fun setOpenCodeWorkspaceIdFor(accountId: String, value: String?) {
        account(accountId)?.setExtra(ProviderAccount.EXTRA_OPENCODE_WORKSPACE, value)
        if (accountId == QuotaProviderType.OPEN_CODE.id) {
            openCodeWorkspaceId = value
        }
    }

    fun isHiddenFromPopup(provider: QuotaProviderType): Boolean = provider.id in hiddenFromQuotaPopup

    fun cachedQuotaJson(provider: QuotaProviderType): String? = cachedQuotaJson(provider.id)

    fun cachedQuotaJson(accountId: String): String? = cachedQuotaJsons[accountId]

    fun setCachedQuotaJson(provider: QuotaProviderType, json: String?) {
        setCachedQuotaJson(provider.id, json)
    }

    fun setCachedQuotaJson(accountId: String, json: String?) {
        if (json == null) {
            cachedQuotaJsons.remove(accountId)
        } else {
            cachedQuotaJsons[accountId] = json
        }
    }

    fun lastUpdate(accountId: String): Long = lastProviderUpdates[accountId] ?: 0L

    fun updateTimestamp(accountId: String) {
        lastProviderUpdates[accountId] = System.currentTimeMillis()
    }

    fun dropAccountData(accountId: String) {
        setCachedQuotaJson(accountId, null)
        lastProviderUpdates.remove(accountId)
        if (lastActiveSource == accountId) {
            lastActiveSource = null
        }
    }

    fun pruneOrphanAccountData() {
        val ids = accounts.map { it.id }.toSet()
        cachedQuotaJsons.keys.filter { it !in ids }.forEach { cachedQuotaJsons.remove(it) }
        lastProviderUpdates.keys.filter { it !in ids }.forEach { lastProviderUpdates.remove(it) }
        if (lastActiveSource != null && lastActiveSource !in ids) {
            lastActiveSource = null
        }
    }

    fun syncLegacyAccountFields() {
        githubEnterpriseHost = account(QuotaProviderType.GITHUB.id)?.extra(ProviderAccount.EXTRA_GITHUB_HOST).orEmpty()
        minimaxRegionPreference = account(QuotaProviderType.MINIMAX.id)?.extra(ProviderAccount.EXTRA_MINIMAX_REGION)
            ?: MiniMaxRegionPreference.AUTO.name
        openCodeWorkspaceId = account(QuotaProviderType.OPEN_CODE.id)?.extra(ProviderAccount.EXTRA_OPENCODE_WORKSPACE)
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

    fun completionsConfig(): CompletionsConfig {
        return CompletionsConfig(
            enabled = openAiProxyEnabled && proxyCompletionsEnabled,
            modelLocalId = proxyCompletionsModelId.trim(),
            useChatAdapter = proxyCompletionsUseChatAdapter,
            maxOutputTokens = CompletionsConfig.clampMaxOutputTokens(proxyCompletionsMaxOutputTokens),
            maxRequestsPerMinute = CompletionsConfig.clampMaxRequestsPerMinute(proxyCompletionsMaxRequestsPerMinute),
            timeoutMillis = CompletionsConfig.timeoutMillis(proxyCompletionsTimeoutSeconds),
            priorityTier = proxyCompletionsPriorityTier,
        )
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

    fun account(id: String): ProviderAccount? = accounts.firstOrNull { it.id == id }

    fun accountsOf(type: QuotaProviderType): List<ProviderAccount> = accounts.filter { it.typeId == type.id }

    fun defaultAccount(type: QuotaProviderType): ProviderAccount? =
        accountsOf(type).firstOrNull { it.isDefault } ?: accountsOf(type).firstOrNull()

    fun accountTypeHasDuplicates(type: QuotaProviderType): Boolean = accountsOf(type).size > 1

    fun accountDisplayName(account: ProviderAccount): String {
        val type = account.providerType() ?: return account.name
        return if (accountTypeHasDuplicates(type)) account.name else type.displayName
    }

    fun accountListLabel(account: ProviderAccount): String {
        val type = account.providerType() ?: return account.name
        return if (accountTypeHasDuplicates(type)) "${type.displayName} (${account.name})" else type.displayName
    }

    fun suggestedAccountName(type: QuotaProviderType): String =
        suggestedAccountName(type, accountsOf(type).map { it.name })

    fun suggestedAccountName(type: QuotaProviderType, existingNames: Collection<String>): String {
        val existing = existingNames.map { it.trim().lowercase() }.toSet()
        if (type.displayName.lowercase() !in existing) return type.displayName
        var index = 2
        while ("${type.displayName} $index".lowercase() in existing) {
            index++
        }
        return "${type.displayName} $index"
    }

    fun duplicateAccountName(account: ProviderAccount): Boolean {
        val name = account.name.trim()
        if (name.isEmpty()) return true
        return accounts.any { other ->
            other.id != account.id &&
                other.typeId == account.typeId &&
                other.name.trim().equals(name, ignoreCase = true)
        }
    }

    fun hasDuplicateAccountNames(): Boolean = accounts.any(::duplicateAccountName)

    fun addAccount(type: QuotaProviderType): ProviderAccount {
        val created = ProviderAccount.create(type, suggestedAccountName(type), isFirstOfType = accountsOf(type).isEmpty())
        accounts.add(created)
        return created
    }

    fun removeAccount(id: String): ProviderAccount? {
        val index = accounts.indexOfFirst { it.id == id }
        if (index < 0) return null
        val removed = accounts.removeAt(index)
        val remaining = accountsOf(removed.providerType() ?: return removed)
        if (removed.isDefault && remaining.isNotEmpty()) {
            remaining.forEach { it.isDefault = false }
            remaining.first().isDefault = true
            remaining.first().allowFailover = false
        }
        return removed
    }

    fun setDefaultAccount(id: String) {
        val account = account(id) ?: return
        val type = account.providerType() ?: return
        accountsOf(type).forEach { it.isDefault = it.id == id }
        account.allowFailover = false
    }

    fun standbyForOthers(type: QuotaProviderType): Boolean =
        accountsOf(type).any { !it.isDefault && it.allowFailover }

    fun setStandbyForOthers(type: QuotaProviderType, enabled: Boolean) {
        accountsOf(type).forEach { account ->
            account.allowFailover = enabled && !account.isDefault
        }
    }

    fun lastUsedSource(): QuotaIndicatorSource {
        if (accounts.isNotEmpty()) {
            val ranked = accounts.mapNotNull { account ->
                val type = account.providerType() ?: return@mapNotNull null
                type to timestampFor(account.id, type)
            }
            val latestAt = ranked.maxOfOrNull { it.second } ?: 0L
            if (latestAt <= 0L) return QuotaIndicatorSource.OPEN_AI
            val order = QuotaProviderRegistry.defaultProviderOrder()
            val latest = order.firstOrNull { type -> ranked.any { it.first == type && it.second == latestAt } }
                ?: ranked.first { it.second == latestAt }.first
            return QuotaIndicatorSource.forProvider(latest)
        }
        val updates = QuotaProviderRegistry.all.associate { registration ->
            val provider = registration.type
            provider to timestampFor(provider.id, provider)
        }
        if (updates.values.max() == 0L) return QuotaIndicatorSource.OPEN_AI
        val latest = QuotaProviderRegistry.defaultProviderOrder().maxByOrNull { updates.getValue(it) }
            ?: return QuotaIndicatorSource.OPEN_AI
        return QuotaIndicatorSource.forProvider(latest)
    }

    private fun timestampFor(accountId: String, type: QuotaProviderType): Long {
        return lastUpdate(accountId).takeIf { it > 0 }
            ?: QuotaSnapshotCache.decode(type, cachedQuotaJson(accountId))?.fetchedAt?.toEpochMilliseconds()
            ?: 0L
    }

    companion object {
        val SUBSCRIPTION_PROXY_SUPPORTED_PROVIDERS: List<QuotaProviderType>
            get() = ProviderCatalog.proxySupportedProviders()

        val DEFAULT_SUBSCRIPTION_PROXY_PROVIDERS: List<String>
            get() = SUBSCRIPTION_PROXY_SUPPORTED_PROVIDERS.map { it.id }

        val DEFAULT_PROVIDER_ORDER: String
            get() = QuotaProviderRegistry.defaultProviderOrderStorageValue()

        fun sanitizeAccounts(raw: List<ProviderAccount>?): List<ProviderAccount> {
            val copied = raw.orEmpty().map { account ->
                account.copy(
                    id = account.id.trim(),
                    typeId = account.typeId.trim(),
                    name = account.name.trim(),
                    extras = account.extras.toMutableMap(),
                )
            }.filter { it.id.isNotEmpty() && it.typeId.isNotEmpty() }
            val byType = copied.groupBy { it.typeId }
            return copied.map { account ->
                val siblings = byType[account.typeId].orEmpty()
                val firstId = siblings.first().id
                val defaultId = siblings.firstOrNull { it.isDefault }?.id ?: firstId
                account.copy(
                    isDefault = account.id == defaultId,
                    allowFailover = account.allowFailover && account.id != defaultId,
                )
            }
        }

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
