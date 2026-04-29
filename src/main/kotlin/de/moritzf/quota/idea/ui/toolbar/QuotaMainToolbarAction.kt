package de.moritzf.quota.idea.ui.toolbar

import com.intellij.ide.impl.ProjectUtil
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.settings.QuotaDisplayMode
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.idea.ui.indicator.QuotaIndicatorComponent
import de.moritzf.quota.idea.ui.indicator.QuotaIndicatorData
import de.moritzf.quota.idea.ui.indicator.QuotaIndicatorLocation
import de.moritzf.quota.idea.ui.popup.QuotaPopupLocation
import de.moritzf.quota.idea.ui.popup.QuotaPopupSupport
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.RightAlignedToolbarAction
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.project.DumbAware
import java.awt.Component
import javax.swing.JComponent

class QuotaMainToolbarAction : AnAction(), CustomComponentAction, RightAlignedToolbarAction, DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val project = event.project
        event.presentation.isEnabledAndVisible =
            project != null &&
                !project.isDisposed &&
                QuotaSettingsState.getInstance().location() == QuotaIndicatorLocation.MAIN_TOOLBAR
    }

    override fun actionPerformed(event: AnActionEvent) {
    }

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
        return QuotaIndicatorComponent(horizontalPadding = 6, onClick = ::showPopup).also(::updateComponent)
    }

    override fun updateCustomComponent(component: JComponent, presentation: Presentation) {
        (component as? QuotaIndicatorComponent)?.let(::updateComponent)
    }

    private fun updateComponent(component: QuotaIndicatorComponent) {
        val service = QuotaUsageService.getInstance()
        component.updateUsage(
            data = service.getEffectiveIndicatorData(),
            displayMode = QuotaDisplayMode.sanitizeFor(
                QuotaIndicatorLocation.MAIN_TOOLBAR,
                QuotaSettingsState.getInstance().displayMode(),
            ),
        )
    }

    private fun showPopup(component: Component, data: QuotaIndicatorData) {
        val project = ProjectUtil.getProjectForComponent(component) ?: return
        val service = QuotaUsageService.getInstance()
        QuotaPopupSupport.showPopup(
            project, component,
            service.getLastQuota(), service.getLastError(),
            service.getLastOpenCodeQuota(), service.getLastOpenCodeError(),
            service.getLastOllamaQuota(), service.getLastOllamaError(),
            service.getLastZaiQuota(), service.getLastZaiError(),
            service.getLastMiniMaxQuota(), service.getLastMiniMaxError(),
            QuotaPopupLocation.BELOW,
        )
    }
}
