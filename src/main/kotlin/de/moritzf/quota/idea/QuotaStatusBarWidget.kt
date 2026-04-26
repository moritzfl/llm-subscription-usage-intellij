package de.moritzf.quota.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.util.messages.MessageBusConnection
import de.moritzf.quota.OpenAiCodexQuota
import de.moritzf.quota.OpenCodeQuota
import de.moritzf.quota.idea.QuotaIndicatorSource
import javax.swing.JComponent

/**
 * Status bar widget that displays the current quota state and shows a detailed popup.
 */
class QuotaStatusBarWidget(private val project: Project) : CustomStatusBarWidget {
    private val connection: MessageBusConnection
    private val widgetComponent = QuotaIndicatorComponent(horizontalPadding = 4) { component, currentQuota, currentError ->
        val service = QuotaUsageService.getInstance()
        val openAiQuota = currentQuota as? OpenAiCodexQuota
        val openCodeQuota = currentQuota as? de.moritzf.quota.OpenCodeQuota
        QuotaPopupSupport.showPopup(
            project, component, openAiQuota, currentError,
            openCodeQuota ?: service.getLastOpenCodeQuota(), service.getLastOpenCodeError(),
            QuotaPopupLocation.ABOVE,
        )
    }
    @Volatile
    private var quota: OpenAiCodexQuota?
    @Volatile
    private var openCodeQuota: OpenCodeQuota?
    @Volatile
    private var error: String?
    @Volatile
    private var openCodeError: String?
    private var statusBar: StatusBar? = null

    init {
        val usageService = QuotaUsageService.getInstance()
        quota = usageService.getLastQuota()
        openCodeQuota = usageService.getLastOpenCodeQuota()
        error = usageService.getLastError()
        openCodeError = usageService.getLastOpenCodeError()
        connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(QuotaUsageListener.TOPIC, object : QuotaUsageListener {
            override fun onQuotaUpdated(updatedQuota: OpenAiCodexQuota?, updatedError: String?) {
                quota = updatedQuota
                error = updatedError
                updateWidget()
            }
            override fun onOpenCodeQuotaUpdated(updatedOpenCodeQuota: OpenCodeQuota?, updatedOpenCodeError: String?) {
                openCodeQuota = updatedOpenCodeQuota
                openCodeError = updatedOpenCodeError
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
            val effectiveQuota = selectEffectiveQuota()
            val effectiveError = selectEffectiveError()
            widgetComponent.updateUsage(effectiveQuota, effectiveError, QuotaSettingsState.getInstance().displayMode())
        }
        statusBar?.updateWidget(ID())
    }

    private fun selectEffectiveQuota(): Any? {
        val source = QuotaSettingsState.getInstance().source()
        return when (source) {
            QuotaIndicatorSource.OPEN_AI -> quota
            QuotaIndicatorSource.OPEN_CODE -> openCodeQuota
            QuotaIndicatorSource.LAST_USED -> when (QuotaSettingsState.getInstance().lastUsedSource()) {
                QuotaIndicatorSource.OPEN_AI -> quota
                QuotaIndicatorSource.OPEN_CODE -> openCodeQuota
                else -> quota
            }
        } as Any?
    }

    private fun selectEffectiveError(): String? {
        val source = QuotaSettingsState.getInstance().source()
        return when (source) {
            QuotaIndicatorSource.OPEN_AI -> error
            QuotaIndicatorSource.OPEN_CODE -> openCodeError
            QuotaIndicatorSource.LAST_USED -> when (QuotaSettingsState.getInstance().lastUsedSource()) {
                QuotaIndicatorSource.OPEN_AI -> error
                QuotaIndicatorSource.OPEN_CODE -> openCodeError
                else -> error
            }
        }
    }
}
