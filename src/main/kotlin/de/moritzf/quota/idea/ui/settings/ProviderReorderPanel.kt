package de.moritzf.quota.idea.ui.settings

import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import de.moritzf.quota.idea.common.QuotaProviderRegistry
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.ui.indicator.ProviderAuthState
import de.moritzf.quota.idea.ui.indicator.ProviderUiRegistry
import java.awt.AlphaComposite
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.Point
import java.awt.RenderingHints
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import javax.swing.AbstractAction
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DropMode
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.TransferHandler
import javax.swing.event.DocumentEvent

internal class ProviderReorderPanel(
    initialOrder: List<QuotaProviderType>,
    private val onOrderChanged: (List<QuotaProviderType>) -> Unit,
    private val onProviderSelected: (QuotaProviderType?) -> Unit,
) : JPanel(BorderLayout()) {

    private val providers = ProviderUiRegistry.all.values
        .map { ProviderInfo(it.type, it.icon) }

    private var currentOrder: List<QuotaProviderType> =
        QuotaProviderRegistry.mergeProviderOrder(
            initialOrder.filter { type -> providers.any { it.type == type } },
        )

    private var selectedProvider: QuotaProviderType =
        currentOrder.firstOrNull() ?: QuotaProviderRegistry.defaultProviderOrder().first()

    private var updatingList = false
    private var dropIndex = -1
    private var draggedType: QuotaProviderType? = null

    private val listModel = CollectionListModel<QuotaProviderType>()
    private val list = object : JBList<QuotaProviderType>(listModel) {
        override fun paint(g: Graphics) {
            super.paint(g)
            paintInsertLine(g)
        }
    }.apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = ProviderListCellRenderer()
        emptyText.text = "No matching providers"
        if (!GraphicsEnvironment.isHeadless()) {
            dragEnabled = true
        }
        dropMode = DropMode.INSERT
        transferHandler = ProviderTransferHandler()
        border = JBUI.Borders.empty(2, 0)
        accessibleContext.accessibleName = "Providers"
    }

    private val filterField = JBTextField().apply {
        emptyText.text = "Filter providers..."
    }

    init {
        isOpaque = false
        background = UIUtil.getPanelBackground()
        preferredSize = Dimension(JBUI.scale(LIST_WIDTH), JBUI.scale(200))
        minimumSize = Dimension(JBUI.scale(200), JBUI.scale(80))

        val header = JBLabel("Providers (drag to reorder)").apply {
            foreground = JBColor.GRAY
            border = JBUI.Borders.emptyBottom(6)
        }

        val north = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = false
            add(header, BorderLayout.NORTH)
            add(filterField, BorderLayout.CENTER)
        }

        add(north, BorderLayout.NORTH)
        add(JBScrollPane(list).apply {
            border = JBUI.Borders.empty()
        }, BorderLayout.CENTER)

        filterField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                rebuildList(notifySelection = true)
            }
        })

        list.addListSelectionListener {
            if (updatingList || list.valueIsAdjusting) {
                return@addListSelectionListener
            }
            val type = list.selectedValue ?: return@addListSelectionListener
            selectedProvider = type
            onProviderSelected(type)
        }

        val inputMap = list.inputMap
        val actionMap = list.actionMap
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, InputEvent.ALT_DOWN_MASK), "moveProviderUp")
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK), "moveProviderDown")
        actionMap.put("moveProviderUp", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                moveSelected(-1)
            }
        })
        actionMap.put("moveProviderDown", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                moveSelected(1)
            }
        })

        rebuildList(notifySelection = false)
        list.setSelectedValue(selectedProvider, true)
    }

    fun getOrder(): List<QuotaProviderType> = currentOrder.toList()

    fun getSelectedProvider(): QuotaProviderType = selectedProvider

    fun visibleProviders(): List<QuotaProviderType> {
        val query = filterField.text.trim()
        if (query.isEmpty()) {
            return currentOrder
        }
        return currentOrder.filter { it.displayName.contains(query, ignoreCase = true) }
    }

    fun setFilterText(text: String) {
        filterField.text = text
    }

    fun setOrder(order: List<QuotaProviderType>) {
        currentOrder = QuotaProviderRegistry.mergeProviderOrder(
            order.filter { type -> providers.any { it.type == type } },
        )
        selectedProvider = currentOrder.firstOrNull() ?: selectedProvider
        if (filterField.text.isNotEmpty()) {
            filterField.text = ""
            return
        }
        rebuildList(notifySelection = false)
        list.setSelectedValue(selectedProvider, true)
        onProviderSelected(selectedProvider)
    }

    fun moveSelected(delta: Int) {
        if (filterField.text.isNotBlank()) {
            return
        }
        val index = currentOrder.indexOf(selectedProvider)
        if (index < 0) {
            return
        }
        val target = (index + delta).coerceIn(0, currentOrder.lastIndex)
        if (target == index) {
            return
        }
        val mutable = currentOrder.toMutableList()
        mutable.removeAt(index)
        mutable.add(target, selectedProvider)
        currentOrder = mutable
        rebuildList(notifySelection = false)
        list.setSelectedValue(selectedProvider, true)
        onOrderChanged(currentOrder)
    }

    private fun rebuildList(notifySelection: Boolean) {
        updatingList = true
        try {
            val visible = visibleProviders()
            listModel.replaceAll(visible)
            if (selectedProvider in visible) {
                list.setSelectedValue(selectedProvider, true)
            } else {
                list.clearSelection()
            }
        } finally {
            updatingList = false
        }
        if (notifySelection) {
            onProviderSelected(list.selectedValue)
        }
    }

    private fun setDropIndex(index: Int) {
        if (dropIndex == index) {
            return
        }
        dropIndex = index
        list.repaint()
    }

    private fun clearDragVisuals() {
        dropIndex = -1
        draggedType = null
        list.repaint()
    }

    private fun paintInsertLine(g: Graphics) {
        if (dropIndex < 0) {
            return
        }
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = JBColor(Color(0x4285F4), Color(0x8AB4F8))
            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.9f)
            val y = insertLineY(dropIndex)
            val left = JBUI.scale(8)
            val right = list.width - JBUI.scale(8)
            val thickness = JBUI.scale(2)
            g2.fillRect(left, y - thickness / 2, (right - left).coerceAtLeast(1), thickness)
            val arrow = JBUI.scale(5)
            g2.fillPolygon(
                intArrayOf(left, left + arrow * 2, left + arrow * 2),
                intArrayOf(y, y - arrow, y + arrow),
                3,
            )
            g2.fillPolygon(
                intArrayOf(right, right - arrow * 2, right - arrow * 2),
                intArrayOf(y, y - arrow, y + arrow),
                3,
            )
        } finally {
            g2.dispose()
        }
    }

    private fun insertLineY(index: Int): Int {
        val count = list.model.size
        if (count == 0) {
            return JBUI.scale(4)
        }
        return if (index >= count) {
            val last = list.getCellBounds(count - 1, count - 1)
            (last?.y ?: 0) + (last?.height ?: 0)
        } else {
            list.getCellBounds(index, index)?.y ?: 0
        }
    }

    private fun createDragPreview(type: QuotaProviderType): Image? {
        val info = providers.find { it.type == type } ?: return null
        val chip = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = true
            background = list.selectionBackground
            border = JBUI.Borders.empty(3, 8)
            add(JBLabel(scaleToSize(info.icon, JBUI.scale(ICON_SIZE), list)))
            add(Box.createHorizontalStrut(JBUI.scale(6)))
            add(JBLabel(type.displayName).apply { foreground = list.selectionForeground })
        }
        val size = chip.preferredSize
        chip.size = size
        chip.doLayout()
        val image = BufferedImage(size.width.coerceAtLeast(1), size.height.coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
        val g2 = image.createGraphics()
        try {
            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.85f)
            chip.paint(g2)
        } finally {
            g2.dispose()
        }
        return image
    }

    private fun moveProvider(draggedId: String, insertIndex: Int) {
        if (filterField.text.isNotBlank()) {
            return
        }
        val draggedType = QuotaProviderType.fromId(draggedId) ?: return
        val draggedIndex = currentOrder.indexOf(draggedType)
        if (draggedIndex < 0) {
            return
        }
        val adjusted = (if (insertIndex > draggedIndex) insertIndex - 1 else insertIndex)
            .coerceIn(0, currentOrder.size - 1)
        if (adjusted == draggedIndex) {
            return
        }
        val mutable = currentOrder.toMutableList()
        mutable.removeAt(draggedIndex)
        mutable.add(adjusted, draggedType)
        currentOrder = mutable
        selectedProvider = draggedType
        rebuildList(notifySelection = false)
        list.setSelectedValue(selectedProvider, true)
        onOrderChanged(currentOrder)
        onProviderSelected(selectedProvider)
    }

    private inner class ProviderTransferHandler : TransferHandler() {
        override fun getSourceActions(c: JComponent): Int {
            return if (filterField.text.isBlank()) MOVE else NONE
        }

        override fun exportAsDrag(comp: JComponent, e: InputEvent, action: Int) {
            if (filterField.text.isNotBlank()) {
                return
            }
            draggedType = list.selectedValue
            list.repaint()
            super.exportAsDrag(comp, e, action)
        }

        override fun createTransferable(c: JComponent): Transferable? {
            val type = list.selectedValue ?: return null
            val preview = createDragPreview(type)
            dragImage = preview
            if (preview != null) {
                dragImageOffset = Point(preview.getWidth(null) / 2, preview.getHeight(null) / 2)
            }
            return StringSelection(type.id)
        }

        override fun exportDone(source: JComponent?, data: Transferable?, action: Int) {
            clearDragVisuals()
        }

        override fun canImport(support: TransferSupport): Boolean {
            if (filterField.text.isNotBlank()) return false
            if (!support.isDrop) return false
            if (!support.isDataFlavorSupported(DataFlavor.stringFlavor)) return false
            support.setShowDropLocation(true)
            val index = (support.dropLocation as? JList.DropLocation)?.index ?: -1
            setDropIndex(index)
            return true
        }

        override fun importData(support: TransferSupport): Boolean {
            if (!canImport(support)) return false
            val draggedId = try {
                support.transferable.getTransferData(DataFlavor.stringFlavor) as String
            } catch (_: Exception) {
                clearDragVisuals()
                return false
            }
            val insertIndex = (support.dropLocation as? JList.DropLocation)?.index ?: run {
                clearDragVisuals()
                return false
            }
            moveProvider(draggedId, insertIndex)
            clearDragVisuals()
            return true
        }
    }

    private inner class ProviderListCellRenderer : ListCellRenderer<QuotaProviderType> {
        private val handle = JBLabel("⋮⋮").apply {
            foreground = JBColor.GRAY
            horizontalAlignment = SwingConstants.CENTER
            border = JBUI.Borders.emptyRight(6)
        }
        private val iconLabel = JBLabel()
        private val nameLabel = JBLabel()
        private val statusLabel = JBLabel("●").apply {
            horizontalAlignment = SwingConstants.CENTER
            border = JBUI.Borders.emptyLeft(8)
        }
        private val row = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = JBUI.Borders.empty(4, 8)
            add(handle)
            add(iconLabel)
            add(Box.createHorizontalStrut(JBUI.scale(8)))
            add(nameLabel)
            add(Box.createHorizontalGlue())
            add(statusLabel)
        }

        override fun getListCellRendererComponent(
            list: JList<out QuotaProviderType>,
            value: QuotaProviderType?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val type = value ?: return row
            val info = providers.find { it.type == type }
            iconLabel.icon = info?.icon?.let { scaleToSize(it, JBUI.scale(ICON_SIZE), iconLabel) }
            nameLabel.text = type.displayName
            val snapshot = statusSnapshot(type)
            val dragging = type == draggedType
            statusLabel.foreground = if (dragging) JBColor.GRAY else statusColor(snapshot.status)
            statusLabel.toolTipText = snapshot.explanation
            row.toolTipText = "${type.displayName} — ${snapshot.explanation}"
            if (isSelected && !dragging) {
                row.background = list.selectionBackground
                row.isOpaque = true
                nameLabel.foreground = list.selectionForeground
                handle.foreground = list.selectionForeground
            } else {
                row.background = list.background
                row.isOpaque = true
                nameLabel.foreground = if (dragging) JBColor.GRAY else list.foreground
                handle.foreground = JBColor.GRAY
            }
            return row
        }
    }

    private data class ProviderInfo(
        val type: QuotaProviderType,
        val icon: Icon,
    )

    companion object {
        private const val LIST_WIDTH = 260
        private const val ICON_SIZE = 16

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

        internal fun statusSnapshot(type: QuotaProviderType): ProviderListStatusSnapshot {
            return statusSnapshot(type, type.id)
        }

        internal fun statusSnapshot(account: de.moritzf.quota.idea.settings.ProviderAccount): ProviderListStatusSnapshot {
            val type = account.providerType() ?: return ProviderListStatusSnapshot(
                ProviderListStatus.NEVER_CONFIGURED,
                ProviderListStatus.explain(ProviderListStatus.NEVER_CONFIGURED, null),
            )
            return statusSnapshot(type, account.id)
        }

        private fun statusSnapshot(type: QuotaProviderType, accountId: String): ProviderListStatusSnapshot {
            val auth = runCatching { ProviderUiRegistry.forType(type).authState(accountId) }
                .getOrDefault(ProviderAuthState.UNKNOWN)
            val service = runCatching { QuotaUsageService.getInstance() }.getOrNull()
            val error = service?.getLastError(accountId)
            val status = ProviderListStatus.resolve(
                auth = auth,
                hasQuota = service?.getLastQuota(accountId) != null,
                hasError = !error.isNullOrBlank(),
            )
            return ProviderListStatusSnapshot(status, ProviderListStatus.explain(status, error))
        }

        internal fun statusColor(status: ProviderListStatus): Color {
            return when (status) {
                ProviderListStatus.OK -> Color(0x4CAF50)
                ProviderListStatus.ERROR -> Color(0xF44336)
                ProviderListStatus.WARNING -> Color(0xFFC107)
                ProviderListStatus.NEVER_CONFIGURED -> JBColor.GRAY
            }
        }

    }
}

internal data class ProviderListStatusSnapshot(
    val status: ProviderListStatus,
    val explanation: String,
)
