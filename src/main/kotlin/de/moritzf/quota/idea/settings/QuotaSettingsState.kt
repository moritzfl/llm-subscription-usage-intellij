package de.moritzf.quota.idea.settings

import com.intellij.openapi.application.ApplicationManager
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
 */
@State(name = "OpenAiUsageQuotaSettings", storages = [Storage("openai-usage-quota.xml")])
@Service(Service.Level.APP)
class QuotaSettingsState : PersistentStateComponent<QuotaSettingsState> {
    var refreshMinutes: Int = 5
    var statusBarDisplayMode: String = QuotaDisplayMode.ICON_ONLY.name
    var indicatorLocation: String = QuotaIndicatorLocation.STATUS_BAR.name
    var indicatorSource: String = QuotaIndicatorSource.OPEN_AI.name
    var lastOpenAiUpdate: Long = 0
    var lastOpenCodeUpdate: Long = 0
    var lastOllamaUpdate: Long = 0
    var lastZaiUpdate: Long = 0
    var lastMiniMaxUpdate: Long = 0
    var lastKimiUpdate: Long = 0
    var lastCursorUpdate: Long = 0
    var hideOpenAiFromQuotaPopup: Boolean = false
    var hideOpenCodeFromQuotaPopup: Boolean = false
    var hideOllamaFromQuotaPopup: Boolean = false
    var hideZaiFromQuotaPopup: Boolean = false
    var hideMiniMaxFromQuotaPopup: Boolean = false
    var hideKimiFromQuotaPopup: Boolean = false
    var hideCursorFromQuotaPopup: Boolean = false
    var lastActiveSource: String? = null
    var openCodeWorkspaceId: String? = null
    var minimaxRegionPreference: String = MiniMaxRegionPreference.AUTO.name
    var cachedOpenAiQuotaJson: String? = null
    var cachedOpenCodeQuotaJson: String? = null
    var cachedOllamaQuotaJson: String? = null
    var cachedZaiQuotaJson: String? = null
    var cachedMiniMaxQuotaJson: String? = null
    var cachedKimiQuotaJson: String? = null
    var cachedCursorQuotaJson: String? = null
    var providerOrder: String = DEFAULT_PROVIDER_ORDER
    var syncIntellijMcpServerUrl: Boolean = false
    var mcpServerSyncTargets: MutableList<McpServerSyncTarget> = mutableListOf()
    var openAiProxyEnabled: Boolean = false
    var openAiProxyPort: Int = OpenAiProxyService.DEFAULT_PORT
    var openAiProxyLogRequests: Boolean = false

    override fun getState(): QuotaSettingsState = this

    override fun loadState(state: QuotaSettingsState) {
        refreshMinutes = state.refreshMinutes
        statusBarDisplayMode = QuotaDisplayMode.fromStorageValue(state.statusBarDisplayMode).name
        indicatorLocation = QuotaIndicatorLocation.fromStorageValue(state.indicatorLocation).name
        indicatorSource = state.indicatorSource
        lastOpenAiUpdate = state.lastOpenAiUpdate
        lastOpenCodeUpdate = state.lastOpenCodeUpdate
        lastOllamaUpdate = state.lastOllamaUpdate
        lastZaiUpdate = state.lastZaiUpdate
        lastMiniMaxUpdate = state.lastMiniMaxUpdate
        lastKimiUpdate = state.lastKimiUpdate
        lastCursorUpdate = state.lastCursorUpdate
        hideOpenAiFromQuotaPopup = state.hideOpenAiFromQuotaPopup
        hideOpenCodeFromQuotaPopup = state.hideOpenCodeFromQuotaPopup
        hideOllamaFromQuotaPopup = state.hideOllamaFromQuotaPopup
        hideZaiFromQuotaPopup = state.hideZaiFromQuotaPopup
        hideMiniMaxFromQuotaPopup = state.hideMiniMaxFromQuotaPopup
        hideKimiFromQuotaPopup = state.hideKimiFromQuotaPopup
        hideCursorFromQuotaPopup = state.hideCursorFromQuotaPopup
        lastActiveSource = state.lastActiveSource
        openCodeWorkspaceId = state.openCodeWorkspaceId
        minimaxRegionPreference = MiniMaxRegionPreference.fromStorageValue(state.minimaxRegionPreference).name
        cachedOpenAiQuotaJson = state.cachedOpenAiQuotaJson
        cachedOpenCodeQuotaJson = state.cachedOpenCodeQuotaJson
        cachedOllamaQuotaJson = state.cachedOllamaQuotaJson
        cachedZaiQuotaJson = state.cachedZaiQuotaJson
        cachedMiniMaxQuotaJson = state.cachedMiniMaxQuotaJson
        cachedKimiQuotaJson = state.cachedKimiQuotaJson
        cachedCursorQuotaJson = state.cachedCursorQuotaJson
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
    }

    fun providerOrderList(): List<QuotaProviderType> {
        val stored = providerOrder.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { QuotaProviderType.fromId(it) }
        return QuotaProviderType.mergeProviderOrder(stored)
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

    fun updateTimestamp(provider: QuotaProviderType) {
        when (provider) {
            QuotaProviderType.OPEN_AI -> lastOpenAiUpdate = System.currentTimeMillis()
            QuotaProviderType.OPEN_CODE -> lastOpenCodeUpdate = System.currentTimeMillis()
            QuotaProviderType.OLLAMA -> lastOllamaUpdate = System.currentTimeMillis()
            QuotaProviderType.ZAI -> lastZaiUpdate = System.currentTimeMillis()
            QuotaProviderType.MINIMAX -> lastMiniMaxUpdate = System.currentTimeMillis()
            QuotaProviderType.KIMI -> lastKimiUpdate = System.currentTimeMillis()
            QuotaProviderType.CURSOR -> lastCursorUpdate = System.currentTimeMillis()
        }
    }

    fun lastUsedSource(): QuotaIndicatorSource {
        val openAiUpdate = lastOpenAiUpdate.takeIf { it > 0 }
            ?: QuotaSnapshotCache.decodeOpenAiQuota(cachedOpenAiQuotaJson)?.fetchedAt?.toEpochMilliseconds()
            ?: 0
        val openCodeUpdate = lastOpenCodeUpdate.takeIf { it > 0 }
            ?: QuotaSnapshotCache.decodeOpenCodeQuota(cachedOpenCodeQuotaJson)?.fetchedAt?.toEpochMilliseconds()
            ?: 0
        val ollamaUpdate = lastOllamaUpdate.takeIf { it > 0 }
            ?: QuotaSnapshotCache.decodeOllamaQuota(cachedOllamaQuotaJson)?.fetchedAt?.toEpochMilliseconds()
            ?: 0
        val zaiUpdate = lastZaiUpdate.takeIf { it > 0 }
            ?: QuotaSnapshotCache.decodeZaiQuota(cachedZaiQuotaJson)?.fetchedAt?.toEpochMilliseconds()
            ?: 0
        val minimaxUpdate = lastMiniMaxUpdate.takeIf { it > 0 }
            ?: QuotaSnapshotCache.decodeMiniMaxQuota(cachedMiniMaxQuotaJson)?.fetchedAt?.toEpochMilliseconds()
            ?: 0
        val kimiUpdate = lastKimiUpdate.takeIf { it > 0 }
            ?: QuotaSnapshotCache.decodeKimiQuota(cachedKimiQuotaJson)?.fetchedAt?.toEpochMilliseconds()
            ?: 0
        val cursorUpdate = lastCursorUpdate.takeIf { it > 0 }
            ?: QuotaSnapshotCache.decodeCursorQuota(cachedCursorQuotaJson)?.fetchedAt?.toEpochMilliseconds()
            ?: 0
        val maxUpdate = maxOf(openAiUpdate, openCodeUpdate, ollamaUpdate, zaiUpdate, minimaxUpdate, kimiUpdate, cursorUpdate)
        if (maxUpdate == 0L) return QuotaIndicatorSource.OPEN_AI
        return when {
            cursorUpdate >= maxUpdate -> QuotaIndicatorSource.CURSOR
            kimiUpdate >= maxUpdate -> QuotaIndicatorSource.KIMI
            minimaxUpdate >= maxUpdate -> QuotaIndicatorSource.MINIMAX
            zaiUpdate >= maxUpdate -> QuotaIndicatorSource.ZAI
            ollamaUpdate >= maxUpdate -> QuotaIndicatorSource.OLLAMA
            openCodeUpdate >= maxUpdate -> QuotaIndicatorSource.OPEN_CODE
            else -> QuotaIndicatorSource.OPEN_AI
        }
    }

    companion object {
        const val DEFAULT_PROVIDER_ORDER =
            "cursor,kimi,minimax,ollama,openai,opencode,zai"

        @JvmStatic
        fun getInstance(): QuotaSettingsState {
            return ApplicationManager.getApplication().getService(QuotaSettingsState::class.java)
        }
    }
}
