package de.moritzf.quota.idea.ui.settings

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import java.awt.AlphaComposite
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.TransferHandler

/**
 * A horizontal panel of draggable provider icons.
 * Reordering fires [onOrderChanged] with the new provider type list.
 */
internal class ProviderReorderPanel(
    initialOrder: List<QuotaProviderType>,
    private val onOrderChanged: (List<QuotaProviderType>) -> Unit,
) : JPanel(BorderLayout()) {

    private val providers = listOf(
        ProviderInfo(QuotaProviderType.KIMI, QuotaIcons.KIMI),
        ProviderInfo(QuotaProviderType.MINIMAX, QuotaIcons.MINIMAX),
        ProviderInfo(QuotaProviderType.OPEN_AI, QuotaIcons.OPENAI),
        ProviderInfo(QuotaProviderType.OPEN_CODE, QuotaIcons.OPENCODE),
        ProviderInfo(QuotaProviderType.OLLAMA, QuotaIcons.OLLAMA),
        ProviderInfo(QuotaProviderType.ZAI, QuotaIcons.ZAI),
    )

    private val iconsPanel = object : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)) {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val idx = dropIndex
            if (idx < 0) return
            val g2 = g as Graphics2D
            g2.color = JBColor(Color(0x4285F4), Color(0x8AB4F8))
            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.9f)
            val x = computeDropLineX(idx)
            val top = JBUI.scale(4)
            val bottom = height - JBUI.scale(4)
            g2.fillRect(x - JBUI.scale(1), top, JBUI.scale(2), bottom - top)
            // draw arrow heads
            val arrowSize = JBUI.scale(5)
            val midY = (top + bottom) / 2
            val xs = intArrayOf(x - arrowSize, x + arrowSize, x)
            val topYs = intArrayOf(top + arrowSize * 2, top + arrowSize * 2, top + arrowSize)
            val botYs = intArrayOf(bottom - arrowSize * 2, bottom - arrowSize * 2, bottom - arrowSize)
            g2.fillPolygon(xs, topYs, 3)
            g2.fillPolygon(xs, botYs, 3)
            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f)
        }
    }.apply {
        isOpaque = false
    }

    private var currentOrder: List<QuotaProviderType> = initialOrder.filter { type -> providers.any { it.type == type } }
        .let { ordered ->
            val remaining = providers.map { it.type }.filter { it !in ordered }
            ordered + remaining
        }

    /** -1 = no active drop target. Otherwise the index where the dragged item would be inserted. */
    private var dropIndex: Int = -1

    init {
        isOpaque = false
        border = JBUI.Borders.empty(6, 0)

        val hintLabel = JBLabel("Drag icons to reorder how providers appear in the quota popup:").apply {
            foreground = JBColor.GRAY
        }

        add(hintLabel, BorderLayout.NORTH)
        add(iconsPanel, BorderLayout.CENTER)
        rebuild()
    }

    fun getOrder(): List<QuotaProviderType> = currentOrder.toList()

    fun setOrder(order: List<QuotaProviderType>) {
        currentOrder = order.filter { type -> providers.any { it.type == type } }
            .let { ordered ->
                val remaining = providers.map { it.type }.filter { it !in ordered }
                ordered + remaining
            }
        rebuild()
    }

    private fun rebuild() {
        iconsPanel.removeAll()
        currentOrder.forEach { type ->
            val provider = providers.find { it.type == type } ?: return@forEach
            iconsPanel.add(createDraggableIcon(provider))
        }
        iconsPanel.revalidate()
        iconsPanel.repaint()
    }

    /** Computes the x-coordinate of the drop indicator line for a given insertion index. */
    private fun computeDropLineX(insertIndex: Int): Int {
        val components = iconsPanel.components
        if (components.isEmpty() || insertIndex < 0) return 0
        if (insertIndex >= components.size) {
            val last = components.last()
            return last.x + last.width + JBUI.scale(4)
        }
        val target = components[insertIndex]
        return target.x - JBUI.scale(4)
    }

    /** Calculates the insertion index from a drop point within the icons panel. */
    private fun insertionIndexFor(point: java.awt.Point): Int {
        val components = iconsPanel.components
        if (components.isEmpty()) return 0
        for (i in components.indices) {
            val c = components[i]
            val mid = c.x + c.width / 2
            if (point.x < mid) return i
        }
        return components.size
    }

    private fun createDraggableIcon(provider: ProviderInfo): JComponent {
        val itemWidth = JBUI.scale(83)
        val itemHeight = JBUI.scale(64)
        return JBLabel(provider.type.displayName, JBLabel.CENTER).apply {
            val scaledIcon = scaleToSize(provider.icon, JBUI.scale(24), this)
            icon = scaledIcon
            horizontalTextPosition = JBLabel.CENTER
            verticalTextPosition = JBLabel.BOTTOM
            iconTextGap = JBUI.scale(4)
            preferredSize = Dimension(itemWidth, itemHeight)
            minimumSize = Dimension(itemWidth, itemHeight)
            maximumSize = Dimension(itemWidth, itemHeight)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.GRAY, 1, true),
                JBUI.Borders.empty(4, 6),
            )
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Drag to reorder ${provider.type.displayName}"
            transferHandler = ProviderTransferHandler()
            putClientProperty("providerId", provider.type.id)

            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    val handler = transferHandler ?: return
                    handler.exportAsDrag(this@apply, e, TransferHandler.MOVE)
                }
            })
        }
    }

    private fun moveProvider(draggedId: String, insertIndex: Int) {
        val draggedType = QuotaProviderType.fromId(draggedId) ?: return
        val mutable = currentOrder.toMutableList()
        val draggedIndex = mutable.indexOf(draggedType)
        if (draggedIndex < 0) return
        mutable.removeAt(draggedIndex)
        // adjust insert index after removal
        val adjustedIndex = if (insertIndex > draggedIndex) insertIndex - 1 else insertIndex
        val clampedIndex = adjustedIndex.coerceIn(0, mutable.size)
        mutable.add(clampedIndex, draggedType)
        currentOrder = mutable
        rebuild()
        onOrderChanged(currentOrder)
    }

    private inner class ProviderTransferHandler : TransferHandler() {
        override fun getSourceActions(c: JComponent): Int = MOVE

        override fun createTransferable(c: JComponent): Transferable? {
            val id = c.getClientProperty("providerId") as? String ?: return null
            return StringSelection(id)
        }

        override fun canImport(support: TransferSupport): Boolean {
            if (!support.isDrop) return false
            if (!support.isDataFlavorSupported(DataFlavor.stringFlavor)) return false
            val target = support.component as? JBLabel ?: return false
            if (target.getClientProperty("providerId") == null) return false

            val pt = support.dropLocation.dropPoint
            val iconsPt = javax.swing.SwingUtilities.convertPoint(target, pt, iconsPanel)
            val newIndex = insertionIndexFor(iconsPt)
            if (newIndex != dropIndex) {
                dropIndex = newIndex
                iconsPanel.repaint()
            }
            return true
        }

        override fun importData(support: TransferSupport): Boolean {
            if (!canImport(support)) return false
            val draggedId = try {
                support.transferable.getTransferData(DataFlavor.stringFlavor) as String
            } catch (_: Exception) {
                return false
            }
            val pt = support.dropLocation.dropPoint
            val target = support.component as? JBLabel ?: return false
            val iconsPt = javax.swing.SwingUtilities.convertPoint(target, pt, iconsPanel)
            val insertIndex = insertionIndexFor(iconsPt)
            dropIndex = -1
            iconsPanel.repaint()
            moveProvider(draggedId, insertIndex)
            return true
        }
    }

    private data class ProviderInfo(
        val type: QuotaProviderType,
        val icon: Icon,
    )

    companion object {
        fun scaleToSize(icon: Icon, targetSize: Int, component: JComponent): Icon {
            val maxDim = maxOf(icon.iconWidth, icon.iconHeight)
            if (maxDim <= 0) return icon
            val scale = targetSize.toFloat() / maxDim
            return if (scale < 1f || maxDim != targetSize) {
                IconUtil.scale(icon, component, scale)
            } else {
                icon
            }
        }
    }
}
