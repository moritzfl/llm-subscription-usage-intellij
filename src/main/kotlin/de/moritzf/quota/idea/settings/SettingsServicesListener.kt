package de.moritzf.quota.idea.settings

import de.moritzf.quota.idea.mcp.McpServerUrlSyncService
import de.moritzf.quota.idea.openai.OpenAiProxyService

/** Lazy application listener also handles Settings opened before the first project. */
class SettingsServicesListener(
    private val reloadProxy: () -> Unit = { OpenAiProxyService.getInstance().reloadFromSettings() },
    private val reloadMcp: () -> Unit = {
        McpServerUrlSyncService.getInstance().reloadFromSettings()
    },
) : QuotaSettingsListener {
    override fun onSettingsChanged() {
        reloadProxy()
        reloadMcp()
    }
}
