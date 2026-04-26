package de.moritzf.quota.idea

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

/**
 * Factory that registers and creates the quota status bar widget.
 */
class QuotaStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ID

    override fun getDisplayName(): String = "LLM Subscription Usage"

    override fun isAvailable(project: Project): Boolean = !project.isDisposed

    override fun createWidget(project: Project): StatusBarWidget = QuotaStatusBarWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) {
        widget.dispose()
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true

    override fun isEnabledByDefault(): Boolean = true

    companion object {
        const val ID: String = "openai.usage.quota.widget"
    }
}
