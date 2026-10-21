package de.moritzf.quota.idea.settings

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.quota.idea.ui.QuotaUiUtil
import java.awt.Container
import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.JViewport
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * Scrolls [content] vertically. The content follows the viewport width as far as its minimum width
 * allows, so text wraps instead of scrolling sideways, and it stretches to fill a taller viewport
 * so a center component can use the spare height.
 */
internal fun verticalScrollPane(content: JComponent): JBScrollPane {
    val view =
        object : BorderLayoutPanel(), Scrollable {
                private var settledWidth = -1

                // Wrapped text and flowing rows learn their height from the width this pass
                // gives them, and Swing drops the revalidate they request during layout. Lay
                // out once more afterwards so the form makes room for them.
                override fun doLayout() {
                    super.doLayout()
                    if (width == settledWidth || !isDisplayable) return
                    settledWidth = width
                    SwingUtilities.invokeLater {
                        if (!isDisplayable) return@invokeLater
                        invalidateSubtree()
                        SwingUtilities.getAncestorOfClass(JScrollPane::class.java, this)?.validate()
                    }
                }

                override fun getPreferredScrollableViewportSize(): Dimension =
                    super.getPreferredSize()

                // Once the viewport is narrower than the minimum, scroll sideways over the minimum
                // width rather than over the full preferred width.
                override fun getPreferredSize(): Dimension {
                    val size = super.getPreferredSize()
                    return if (scrollableTracksViewportWidth) size
                    else Dimension(minimumSize.width, size.height)
                }

                override fun getScrollableTracksViewportWidth(): Boolean =
                    (parent as? JViewport)?.let { it.width >= minimumSize.width } ?: true

                override fun getScrollableTracksViewportHeight(): Boolean =
                    (parent as? JViewport)?.let { it.height >= preferredSize.height } ?: false

                override fun getScrollableUnitIncrement(
                    visibleRect: Rectangle,
                    orientation: Int,
                    direction: Int,
                ): Int = JBUI.scale(16)

                override fun getScrollableBlockIncrement(
                    visibleRect: Rectangle,
                    orientation: Int,
                    direction: Int,
                ): Int {
                    val extent =
                        if (orientation == SwingConstants.VERTICAL) visibleRect.height
                        else visibleRect.width
                    return (extent - JBUI.scale(16)).coerceAtLeast(JBUI.scale(16))
                }
            }
            .apply {
                isOpaque = false
                addToCenter(content)
            }
    return JBScrollPane(view).apply {
        border = JBUI.Borders.empty()
        isOverlappingScrollBar = false
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
    }
}

private fun Container.invalidateSubtree() {
    invalidate()
    components.forEach { if (it is Container) it.invalidateSubtree() else it.invalidate() }
}

/** Label text that wraps to the label's width. Place the label with `align(AlignX.FILL)`. */
internal fun wrappingHtml(text: String): String = "<html>${QuotaUiUtil.escapeHtml(text)}</html>"

/** [wrappingHtml] behind a status dot in [colorHex]. */
internal fun statusDotHtml(text: String, colorHex: String): String =
    "<html><span style=\"color: $colorHex\">●</span>&nbsp;${QuotaUiUtil.escapeHtml(text)}</html>"

/** Lets a combo shrink with a narrow form instead of fixing the form's minimum width. */
internal fun <T : ComboBox<*>> T.shrinkable(): T = apply {
    isUsePreferredSizeAsMinimum = false
    minimumSize = Dimension(0, minimumSize.height)
}
