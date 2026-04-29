package de.moritzf.quota.idea.ui.toolbar

import com.intellij.openapi.application.ApplicationManager
import de.moritzf.quota.idea.common.QuotaUsageListener
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.settings.QuotaSettingsListener
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.idea.ui.indicator.QuotaIndicatorComponent
import de.moritzf.quota.idea.ui.indicator.QuotaIndicatorLocation
import de.moritzf.quota.idea.ui.popup.QuotaPopupLocation
import de.moritzf.quota.idea.ui.popup.QuotaPopupSupport
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.util.messages.MessageBusConnection
import javax.swing.JComponent

/**
 * Status bar widget that displays the current quota state and shows a detailed popup.
 */
class QuotaStatusBarWidget(private val project: Project) : CustomStatusBarWidget {
    private val connection: MessageBusConnection
    private val widgetComponent = QuotaIndicatorComponent(horizontalPadding = 4) { component, data ->
        val service = QuotaUsageService.getInstance()
        QuotaPopupSupport.showPopup(
            project, component,
            service.getLastQuota(), service.getLastError(),
            service.getLastOpenCodeQuota(), service.getLastOpenCodeError(),
            service.getLastOllamaQuota(), service.getLastOllamaError(),
            service.getLastZaiQuota(), service.getLastZaiError(),
            QuotaPopupLocation.ABOVE,
        )
    }
    private var statusBar: StatusBar? = null

    init {
        connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(QuotaUsageListener.TOPIC, object : QuotaUsageListener {
            override fun onQuotaUpdated(quota: de.moritzf.quota.openai.OpenAiCodexQuota?, error: String?) {
                updateWidget()
            }
            override fun onOpenCodeQuotaUpdated(quota: de.moritzf.quota.opencode.OpenCodeQuota?, error: String?) {
                updateWidget()
            }
            override fun onOllamaQuotaUpdated(quota: de.moritzf.quota.ollama.OllamaQuota?, error: String?) {
                updateWidget()
            }
            override fun onZaiQuotaUpdated(quota: de.moritzf.quota.zai.ZaiQuota?, error: String?) {
                updateWidget()
            }
        })
        connection.subscribe(QuotaSettingsListener.TOPIC, QuotaSettingsListener { updateWidget() })
        updateWidget()
    }

    override fun ID(): String = QuotaStatusBarWidgetFactory.ID

    override fun getComponent(): JComponent = widgetComponent

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        updateWidget()
    }

    override fun dispose() {
        connection.dispose()
    }

    private fun updateWidget() {
        val inStatusBar = QuotaSettingsState.getInstance().location() == QuotaIndicatorLocation.STATUS_BAR
        widgetComponent.isVisible = inStatusBar
        if (inStatusBar) {
            widgetComponent.updateUsage(
                data = QuotaUsageService.getInstance().getEffectiveIndicatorData(),
                displayMode = QuotaSettingsState.getInstance().displayMode(),
            )
        }
        statusBar?.updateWidget(ID())
    }
}
