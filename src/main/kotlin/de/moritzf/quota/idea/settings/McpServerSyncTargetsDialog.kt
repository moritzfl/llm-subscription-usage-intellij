package de.moritzf.quota.idea.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.quota.idea.mcp.McpJsonTargetUpdater
import de.moritzf.quota.idea.mcp.McpServerSyncTarget
import de.moritzf.quota.idea.mcp.McpServerTransport
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.AbstractCellEditor
import javax.swing.DefaultCellEditor
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.TableCellEditor
import javax.swing.table.DefaultTableModel

internal class McpServerSyncTargetsDialog(
    parent: Component,
    initialTargets: List<McpServerSyncTarget>,
) : DialogWrapper(parent, true) {
    private val model = object : DefaultTableModel(COLUMNS, 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = true

        override fun getColumnClass(columnIndex: Int): Class<*> {
            return if (columnIndex == TRANSPORT_COLUMN) McpServerTransport::class.java else String::class.java
        }
    }

    private val table = JBTable(model).apply {
        preferredScrollableViewportSize = Dimension(JBUI.scale(760), JBUI.scale(180))
        rowHeight = JBUI.scale(26)
        fillsViewportHeight = true
        columnModel.getColumn(FILE_COLUMN).preferredWidth = JBUI.scale(320)
        columnModel.getColumn(FILE_COLUMN).cellEditor = JsonFileCellEditor()
        columnModel.getColumn(PROPERTY_COLUMN).preferredWidth = JBUI.scale(300)
        columnModel.getColumn(TRANSPORT_COLUMN).preferredWidth = JBUI.scale(140)
        columnModel.getColumn(TRANSPORT_COLUMN).cellEditor = DefaultCellEditor(
            JComboBox(McpServerTransport.entries.toTypedArray()),
        )
    }

    init {
        title = "IntelliJ MCP Server URL Sync Targets"
        initialTargets.forEach { addTarget(it) }
        init()
    }

    fun targets(): List<McpServerSyncTarget> = readTargets(includeIncomplete = false)

    override fun createCenterPanel(): JComponent {
        val help = JBLabel(
            "Use dot paths like mcpServers.jetbrains.url or JSON Pointer paths like /mcpServers/jetbrains/url.",
        ).apply {
            foreground = JBColor.GRAY
            border = JBUI.Borders.emptyBottom(8)
        }

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(JButton("Add").apply {
                addActionListener { addTarget() }
            })
            add(JButton("Remove").apply {
                addActionListener { removeSelectedTargets() }
            })
        }

        return BorderLayoutPanel().apply {
            border = JBUI.Borders.empty(8)
            addToTop(help)
            addToCenter(JBScrollPane(table))
            addToBottom(buttons)
        }
    }

    override fun doValidate(): ValidationInfo? {
        readTargets(includeIncomplete = true).forEachIndexed { index, target ->
            if (target.jsonFilePath.isBlank()) {
                return ValidationInfo("JSON file path is required on row ${index + 1}.", table)
            }
            if (target.jsonPropertyPath.isBlank()) {
                return ValidationInfo("JSON property path is required on row ${index + 1}.", table)
            }
            runCatching { McpJsonTargetUpdater.parsePropertyPath(target.jsonPropertyPath) }
                .onFailure { return ValidationInfo("JSON property path is invalid on row ${index + 1}: ${it.message}", table) }
        }
        return null
    }

    private fun addTarget(target: McpServerSyncTarget = McpServerSyncTarget()) {
        model.addRow(
            arrayOf<Any>(
                target.jsonFilePath,
                target.jsonPropertyPath.ifBlank { DEFAULT_PROPERTY_PATH },
                target.transport(),
            ),
        )
    }

    private fun removeSelectedTargets() {
        table.selectedRows
            .map(table::convertRowIndexToModel)
            .sortedDescending()
            .forEach(model::removeRow)
    }

    private fun readTargets(includeIncomplete: Boolean): List<McpServerSyncTarget> {
        table.cellEditor?.stopCellEditing()
        return (0 until model.rowCount).mapNotNull { row ->
            val jsonFilePath = (model.getValueAt(row, FILE_COLUMN) as? String).orEmpty().trim()
            val jsonPropertyPath = (model.getValueAt(row, PROPERTY_COLUMN) as? String).orEmpty().trim()
            val transport = when (val value = model.getValueAt(row, TRANSPORT_COLUMN)) {
                is McpServerTransport -> value
                is String -> McpServerTransport.fromStorageValue(value)
                else -> McpServerTransport.SSE
            }
            if (!includeIncomplete && jsonFilePath.isBlank() && jsonPropertyPath.isBlank()) {
                return@mapNotNull null
            }
            McpServerSyncTarget(
                jsonFilePath = jsonFilePath,
                jsonPropertyPath = jsonPropertyPath,
                transportType = transport.name,
            )
        }
    }

    private class JsonFileCellEditor : AbstractCellEditor(), TableCellEditor {
        private val textFieldWithBrowseButton = TextFieldWithBrowseButton()

        init {
            val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json")
                .withTitle("Select JSON File")
                .withDescription("Choose the JSON configuration file to update.")
                .withShowHiddenFiles(true)
                .apply { setForcedToUseIdeaFileChooser(true) }
            textFieldWithBrowseButton.addBrowseFolderListener(null, descriptor)
        }

        override fun getTableCellEditorComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            row: Int,
            column: Int,
        ): Component {
            textFieldWithBrowseButton.text = value as? String ?: ""
            return textFieldWithBrowseButton
        }

        override fun getCellEditorValue(): Any = textFieldWithBrowseButton.text
    }

    companion object {
        private val COLUMNS = arrayOf("JSON file", "JSON property path", "Transport")
        private const val FILE_COLUMN = 0
        private const val PROPERTY_COLUMN = 1
        private const val TRANSPORT_COLUMN = 2
        private const val DEFAULT_PROPERTY_PATH = "mcpServers.jetbrains.url"
    }
}
