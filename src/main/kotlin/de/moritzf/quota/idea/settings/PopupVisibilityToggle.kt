package de.moritzf.quota.idea.settings

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Component
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon

internal class PopupVisibilityToggle : JBLabel() {
    var isHidden: Boolean = false
        set(value) {
            if (field == value) {
                return
            }
            field = value
            refresh()
        }

    init {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        iconTextGap = JBUI.scale(6)
        addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    isHidden = !isHidden
                }
            }
        )
        refresh()
    }

    private fun refresh() {
        if (isHidden) {
            icon = HIDDEN_ICON
            text = "Hidden from quota popup (click to show)"
            foreground = JBColor.GRAY
            toolTipText = "Show this provider in the quota popup"
        } else {
            icon = SHOWN_ICON
            text = "Shown in quota popup (click to hide)"
            foreground = JBColor.namedColor("Label.foreground", JBColor.foreground())
            toolTipText = "Hide this provider from the quota popup"
        }
    }

    private companion object {
        val SHOWN_ICON: Icon = AllIcons.Actions.Show
        val HIDDEN_ICON: Icon = CrossedOutIcon(AllIcons.Actions.Show)
    }
}

private class CrossedOutIcon(private val base: Icon) : Icon {
    override fun getIconWidth(): Int = base.iconWidth

    override fun getIconHeight(): Int = base.iconHeight

    override fun paintIcon(c: Component, g: Graphics, x: Int, y: Int) {
        base.paintIcon(c, g, x, y)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = JBColor.GRAY
            g2.stroke =
                BasicStroke(JBUI.scale(2).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val inset = JBUI.scale(2)
            g2.drawLine(x + inset, y + iconHeight - inset, x + iconWidth - inset, y + inset)
        } finally {
            g2.dispose()
        }
    }
}
