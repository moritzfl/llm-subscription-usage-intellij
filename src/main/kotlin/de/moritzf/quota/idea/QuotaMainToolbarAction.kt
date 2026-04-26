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
import de.moritzf.quota.idea.QuotaIndicatorSource
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
        val effectiveQuota = selectEffectiveQuota(service)
        val effectiveError = selectEffectiveError(service)
        component.updateUsage(
            quota = effectiveQuota,
            error = effectiveError,
            displayMode = QuotaDisplayMode.sanitizeFor(
                QuotaIndicatorLocation.MAIN_TOOLBAR,
                QuotaSettingsState.getInstance().displayMode(),
            ),
        )
    }

    private fun selectEffectiveQuota(service: QuotaUsageService): Any? {
        val source = QuotaSettingsState.getInstance().source()
        return when (source) {
            QuotaIndicatorSource.OPEN_AI -> service.getLastQuota()
            QuotaIndicatorSource.OPEN_CODE -> service.getLastOpenCodeQuota()
            QuotaIndicatorSource.LAST_USED -> when (QuotaSettingsState.getInstance().lastUsedSource()) {
                QuotaIndicatorSource.OPEN_AI -> service.getLastQuota()
                QuotaIndicatorSource.OPEN_CODE -> service.getLastOpenCodeQuota()
                else -> service.getLastQuota()
            }
        } as Any?
    }

    private fun selectEffectiveError(service: QuotaUsageService): String? {
        val source = QuotaSettingsState.getInstance().source()
        return when (source) {
            QuotaIndicatorSource.OPEN_AI -> service.getLastError()
            QuotaIndicatorSource.OPEN_CODE -> service.getLastOpenCodeError()
            QuotaIndicatorSource.LAST_USED -> when (QuotaSettingsState.getInstance().lastUsedSource()) {
                QuotaIndicatorSource.OPEN_AI -> service.getLastError()
                QuotaIndicatorSource.OPEN_CODE -> service.getLastOpenCodeError()
                else -> service.getLastError()
            }
        }
    }

    private fun showPopup(component: Component, quota: Any?, error: String?) {
        val project = ProjectUtil.getProjectForComponent(component) ?: return
        val openAiQuota = quota as? OpenAiCodexQuota
        val openCodeQuota = quota as? de.moritzf.quota.OpenCodeQuota
        val service = QuotaUsageService.getInstance()
        QuotaPopupSupport.showPopup(
            project, component, openAiQuota, error,
            openCodeQuota ?: service.getLastOpenCodeQuota(), service.getLastOpenCodeError(),
            QuotaPopupLocation.BELOW,
        )
    }
}
