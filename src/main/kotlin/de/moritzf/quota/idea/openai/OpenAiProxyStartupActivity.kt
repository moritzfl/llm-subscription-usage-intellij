package de.moritzf.quota.idea.openai

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class OpenAiProxyStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        OpenAiProxyService.getInstance().reloadFromSettings()
    }
}
