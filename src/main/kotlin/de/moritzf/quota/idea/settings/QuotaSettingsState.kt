package de.moritzf.quota.idea.settings

import com.intellij.openapi.application.ApplicationManager
import de.moritzf.quota.idea.common.QuotaSnapshotCache
import de.moritzf.quota.idea.ui.indicator.QuotaIndicatorLocation
import de.moritzf.quota.idea.ui.indicator.QuotaIndicatorSource
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
    var hideOpenAiFromQuotaPopup: Boolean = false
    var hideOpenCodeFromQuotaPopup: Boolean = false
    var hideOllamaFromQuotaPopup: Boolean = false
    var openCodeWorkspaceId: String? = null
    var cachedOpenAiQuotaJson: String? = null
    var cachedOpenCodeQuotaJson: String? = null
    var cachedOllamaQuotaJson: String? = null

    override fun getState(): QuotaSettingsState = this

    override fun loadState(state: QuotaSettingsState) {
        refreshMinutes = state.refreshMinutes
        statusBarDisplayMode = QuotaDisplayMode.fromStorageValue(state.statusBarDisplayMode).name
        indicatorLocation = QuotaIndicatorLocation.fromStorageValue(state.indicatorLocation).name
        indicatorSource = state.indicatorSource
        lastOpenAiUpdate = state.lastOpenAiUpdate
        lastOpenCodeUpdate = state.lastOpenCodeUpdate
        lastOllamaUpdate = state.lastOllamaUpdate
        hideOpenAiFromQuotaPopup = state.hideOpenAiFromQuotaPopup
        hideOpenCodeFromQuotaPopup = state.hideOpenCodeFromQuotaPopup
        hideOllamaFromQuotaPopup = state.hideOllamaFromQuotaPopup
        openCodeWorkspaceId = state.openCodeWorkspaceId
        cachedOpenAiQuotaJson = state.cachedOpenAiQuotaJson
        cachedOpenCodeQuotaJson = state.cachedOpenCodeQuotaJson
        cachedOllamaQuotaJson = state.cachedOllamaQuotaJson
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

    fun setSource(source: QuotaIndicatorSource) {
        indicatorSource = source.name
    }

    fun updateTimestamp(provider: String) {
        when (provider) {
            "openai" -> lastOpenAiUpdate = System.currentTimeMillis()
            "opencode" -> lastOpenCodeUpdate = System.currentTimeMillis()
            "ollama" -> lastOllamaUpdate = System.currentTimeMillis()
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
        return when {
            ollamaUpdate >= openAiUpdate && ollamaUpdate >= openCodeUpdate -> QuotaIndicatorSource.OLLAMA
            openCodeUpdate >= openAiUpdate && openCodeUpdate >= ollamaUpdate -> QuotaIndicatorSource.OPEN_CODE
            else -> QuotaIndicatorSource.OPEN_AI
        }
    }

    companion object {
        @JvmStatic
        fun getInstance(): QuotaSettingsState {
            return ApplicationManager.getApplication().getService(QuotaSettingsState::class.java)
        }
    }
}
