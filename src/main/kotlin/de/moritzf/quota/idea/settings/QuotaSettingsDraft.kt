package de.moritzf.quota.idea.settings

import de.moritzf.quota.idea.mcp.McpServerSyncTarget

/** Editable configuration only: applying a dialog must never overwrite live quota caches. */
internal data class QuotaSettingsDraft(
    val location: String,
    val displayMode: String,
    val source: String,
    val accounts: List<ProviderAccount>,
    val syncMcp: Boolean,
    val syncTargets: List<McpServerSyncTarget>,
    val proxyEnabled: Boolean,
    val proxyPort: Int,
    val proxyLogging: Boolean,
    val proxyProviders: List<String>,
    val completionsEnabled: Boolean,
    val completionsModel: String,
    val completionsAdapter: Boolean,
    val completionsTokens: Int,
    val completionsRpm: Int,
    val completionsTimeout: Int,
    val completionsPriority: Boolean,
) {
    fun validate() {
        require(accounts.all { it.name.isNotBlank() }) { "Account names cannot be blank." }
        require(
            accounts
                .groupBy { it.typeId }
                .values
                .all { rows ->
                    rows.map { it.name.trim().lowercase() }.distinct().size == rows.size
                }
        ) {
            "Account names must be unique per provider."
        }
    }

    fun applyTo(state: QuotaSettingsState) =
        synchronized(state) {
            state.indicatorLocation = location
            state.statusBarDisplayMode = displayMode
            state.indicatorSource = source
            state.accounts = QuotaSettingsState.sanitizeAccounts(accounts).toMutableList()
            state.syncLegacyAccountFields()
            state.syncIntellijMcpServerUrl = syncMcp
            state.mcpServerSyncTargets = syncTargets.map { it.copy() }.toMutableList()
            state.openAiProxyEnabled = proxyEnabled
            state.openAiProxyPort = proxyPort
            state.openAiProxyLogRequests = proxyLogging
            state.subscriptionProxyEnabledProviders = proxyProviders.toMutableList()
            state.proxyCompletionsEnabled = completionsEnabled
            state.proxyCompletionsModelId = completionsModel
            state.proxyCompletionsUseChatAdapter = completionsAdapter
            state.proxyCompletionsMaxOutputTokens = completionsTokens
            state.proxyCompletionsMaxRequestsPerMinute = completionsRpm
            state.proxyCompletionsTimeoutSeconds = completionsTimeout
            state.proxyCompletionsPriorityTier = completionsPriority
        }

    companion object {
        fun from(state: QuotaSettingsState): QuotaSettingsDraft =
            synchronized(state) {
                QuotaSettingsDraft(
                    state.indicatorLocation,
                    state.statusBarDisplayMode,
                    state.indicatorSource,
                    state.accounts.map { it.snapshot() },
                    state.syncIntellijMcpServerUrl,
                    state.mcpServerSyncTargets.map { it.normalized() },
                    state.openAiProxyEnabled,
                    state.openAiProxyPort,
                    state.openAiProxyLogRequests,
                    state.subscriptionProxyEnabledProviders.toList(),
                    state.proxyCompletionsEnabled,
                    state.proxyCompletionsModelId,
                    state.proxyCompletionsUseChatAdapter,
                    state.proxyCompletionsMaxOutputTokens,
                    state.proxyCompletionsMaxRequestsPerMinute,
                    state.proxyCompletionsTimeoutSeconds,
                    state.proxyCompletionsPriorityTier,
                )
            }
    }
}
