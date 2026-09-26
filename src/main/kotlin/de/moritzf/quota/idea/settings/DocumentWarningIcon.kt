package de.moritzf.quota.idea.settings

import com.intellij.icons.AllIcons
import com.intellij.ide.HelpTooltip
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import de.moritzf.quota.idea.ui.QuotaUiUtil
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel

/** Warning mark beside a document control. Hover or click shows a plain formatted explainer. */
internal class DocumentWarningIcon : JBLabel(AllIcons.General.Warning) {
    private val tooltip = HelpTooltip()
        .setNeverHideOnTimeout(true)
        .setLocation(HelpTooltip.Alignment.HELP_BUTTON)
    private var clickPopup: JBPopup? = null
    private var title = ""
    private var body = ""

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
        this.title = title
        body = text?.trim().orEmpty()
        isVisible = body.isNotEmpty()
        parent?.revalidate()
        parent?.repaint()
        // The protected field is still null until the getter creates the context.
        getAccessibleContext().accessibleName = title
        getAccessibleContext().accessibleDescription = body
        if (body.isEmpty()) {
            HelpTooltip.hide(this)
            clickPopup?.cancel()
            return
        }
        tooltip.setPlainTextTitle(title)
        tooltip.setDescription(description(body))
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
        if (body.isEmpty()) return
        HelpTooltip.hide(this)
        clickPopup?.cancel()
        val panel = explainerPanel(title, body)
        clickPopup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, panel)
            .setRequestFocus(false)
            .setCancelOnClickOutside(true)
            .createPopup()
            .also { it.showUnderneathOf(this) }
    }

    private fun description(text: String): HtmlChunk {
        val paragraphs = text.split("\n\n").filter { it.isNotBlank() }.map { HtmlChunk.text(it).wrapWith(HtmlChunk.p()) }
        return HtmlChunk.fragment(*paragraphs.toTypedArray())
    }

    private fun explainerPanel(title: String, body: String): JComponent {
        val paragraphs = body.split("\n\n").filter { it.isNotBlank() }
            .joinToString("") { "<p>${QuotaUiUtil.escapeHtml(it)}</p>" }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 10)
            add(JBLabel("<html><body style='width: 420px'><b>${QuotaUiUtil.escapeHtml(title)}</b>$paragraphs</body></html>"))
        }
    }
}
