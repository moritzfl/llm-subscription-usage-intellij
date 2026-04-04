package de.moritzf.quota.idea

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.RightAlignedToolbarAction
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.project.DumbAware
import de.moritzf.quota.OpenAiCodexQuota
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
        component.updateUsage(
            quota = QuotaUsageService.getInstance().getLastQuota(),
            error = QuotaUsageService.getInstance().getLastError(),
            displayMode = QuotaSettingsState.getInstance().displayMode(),
        )
    }

    private fun showPopup(component: Component, quota: OpenAiCodexQuota?, error: String?) {
        val project = ProjectUtil.getProjectForComponent(component) ?: return
        QuotaPopupSupport.showPopup(project, component, quota, error, QuotaPopupLocation.BELOW)
    }
}
