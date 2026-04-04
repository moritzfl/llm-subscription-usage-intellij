package de.moritzf.quota.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.util.messages.MessageBusConnection
import de.moritzf.quota.OpenAiCodexQuota
import javax.swing.JComponent

/**
 * Status bar widget that displays the current quota state and shows a detailed popup.
 */
class QuotaStatusBarWidget(private val project: Project) : CustomStatusBarWidget {
    private val connection: MessageBusConnection
    private val widgetComponent = QuotaIndicatorComponent(horizontalPadding = 4) { component, currentQuota, currentError ->
        QuotaPopupSupport.showPopup(project, component, currentQuota, currentError, QuotaPopupLocation.ABOVE)
    }
    @Volatile
    private var quota: OpenAiCodexQuota?
    @Volatile
    private var error: String?
    private var statusBar: StatusBar? = null

    init {
        val usageService = QuotaUsageService.getInstance()
        quota = usageService.getLastQuota()
        error = usageService.getLastError()
        connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(QuotaUsageListener.TOPIC, QuotaUsageListener { updatedQuota, updatedError ->
            quota = updatedQuota
            error = updatedError
            updateWidget()
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
            widgetComponent.updateUsage(quota, error, QuotaSettingsState.getInstance().displayMode())
        }
        statusBar?.updateWidget(ID())
    }
}
