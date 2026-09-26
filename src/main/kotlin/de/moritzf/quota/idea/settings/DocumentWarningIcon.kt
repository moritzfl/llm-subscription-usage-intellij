package de.moritzf.quota.idea.settings

import com.intellij.icons.AllIcons
import com.intellij.ide.HelpTooltip
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.ui.components.JBLabel
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

/** Warning mark beside a document control. Hover or click shows a plain formatted explainer. */
internal class DocumentWarningIcon : JBLabel(AllIcons.General.Warning) {
    private val tooltip = HelpTooltip()
        .setNeverHideOnTimeout(true)
        .setLocation(HelpTooltip.Alignment.HELP_BUTTON)
    private var clickPopup: JBPopup? = null

    init {
        isVisible = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                if (!isVisible || event.button != MouseEvent.BUTTON1) return
                showNow()
            }
        })
    }

    fun setExplainer(title: String, text: String?) {
        val body = text?.trim().orEmpty()
        isVisible = body.isNotEmpty()
        parent?.revalidate()
        parent?.repaint()
        accessibleContext.accessibleName = title
        accessibleContext.accessibleDescription = body
        if (body.isEmpty()) {
            HelpTooltip.hide(this)
            clickPopup?.cancel()
            return
        }
        tooltip.setTitle(title)
        tooltip.setDescription(body.replace("\n\n", "<p>"))
        if (isDisplayable) installTooltip()
    }

    override fun addNotify() {
        super.addNotify()
        if (isVisible) installTooltip()
    }

    override fun removeNotify() {
        clickPopup?.cancel()
        HelpTooltip.dispose(this)
        super.removeNotify()
    }

    private fun installTooltip() {
        HelpTooltip.dispose(this)
        tooltip.installOn(this)
    }

    private fun showNow() {
        HelpTooltip.hide(this)
        clickPopup?.cancel()
        clickPopup = HelpTooltip.initPopupBuilder(tooltip.createTipPanel())
            .setRequestFocus(false)
            .setCancelOnClickOutside(true)
            .createPopup()
            .also { it.showUnderneathOf(this) }
    }
}
