package de.moritzf.quota.idea.settings

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.options.ex.Settings
import com.intellij.openapi.ui.Messages
import javax.swing.JComponent

internal object AiAssistantSettingsNavigator {
    const val PROVIDERS_AND_API_KEYS_ID = "ml.llm.LLMThirdPartyAiProvidersConfigurable"
    const val AI_COMPLETION_SEARCH = "AI Completion"

    fun openProvidersAndApiKeys(invoker: JComponent) {
        open(invoker, searchText = null)
    }

    fun openAiCompletion(invoker: JComponent) {
        open(invoker, searchText = AI_COMPLETION_SEARCH)
    }

    private fun open(invoker: JComponent, searchText: String?) {
        val context = DataManager.getInstance().getDataContext(invoker)
        val settings = Settings.KEY.getData(context)
        val configurable = settings?.find(PROVIDERS_AND_API_KEYS_ID)
        if (settings != null) {
            if (configurable == null) {
                Messages.showInfoMessage(
                    invoker,
                    "Install JetBrains AI Assistant to open Providers & API keys.",
                    "AI Assistant",
                )
                return
            }
            if (searchText.isNullOrBlank()) {
                settings.select(configurable)
            } else {
                settings.select(configurable, searchText)
            }
            return
        }
        val project = CommonDataKeys.PROJECT.getData(context)
        ShowSettingsUtil.getInstance().showSettingsDialog(
            project,
            { (it as? SearchableConfigurable)?.id == PROVIDERS_AND_API_KEYS_ID },
            null,
        )
    }
}
