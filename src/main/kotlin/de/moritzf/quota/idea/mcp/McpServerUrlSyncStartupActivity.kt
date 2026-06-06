package de.moritzf.quota.idea.mcp

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

class McpServerUrlSyncStartupActivity : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        McpServerUrlSyncService.getInstance().reloadFromSettings()
    }
}
