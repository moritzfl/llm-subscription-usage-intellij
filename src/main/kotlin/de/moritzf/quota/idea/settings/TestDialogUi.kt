package de.moritzf.quota.idea.settings

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

internal object TestDialogUi {
    fun codeBlock(text: String, height: Int = 96, wrapWords: Boolean = true): JComponent {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val area =
            JBTextArea(text).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = wrapWords
                font = Font(scheme.editorFontName, Font.PLAIN, scheme.editorFontSize)
                background = UIUtil.getTextFieldBackground()
                border = JBUI.Borders.empty(8)
            }
        return JBScrollPane(area).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 1)
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            preferredSize = Dimension(JBUI.scale(480), JBUI.scale(height))
        }
    }

    fun note(text: String) = JBLabel(text).apply { foreground = UIUtil.getContextHelpForeground() }

    fun slot() = JPanel(BorderLayout()).apply { isOpaque = false }

    fun replace(slot: JPanel, component: JComponent) {
        slot.removeAll()
        slot.add(component, BorderLayout.NORTH)
        slot.revalidate()
        slot.repaint()
    }

    fun clear(slot: JPanel) {
        slot.removeAll()
        slot.revalidate()
        slot.repaint()
    }
}
