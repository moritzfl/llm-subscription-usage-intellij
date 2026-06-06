package de.moritzf.quota.idea.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.quota.idea.mcp.McpJsonTargetUpdater
import de.moritzf.quota.idea.mcp.McpServerSyncTarget
import de.moritzf.quota.idea.mcp.McpServerTransport
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import javax.swing.AbstractCellEditor
import javax.swing.DefaultCellEditor
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellEditor
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

@OptIn(ExperimentalSerializationApi::class)
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
        columnModel.getColumn(PROPERTY_COLUMN).cellEditor = JsonPropertyPathCellEditor(::chooseJsonPropertyPath)
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
            if (target.jsonPropertyPath.isBlank()) {
                return@forEachIndexed
            }
            if (target.jsonFilePath.isBlank()) {
                return ValidationInfo("JSON file path is required on row ${index + 1}.", table)
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
                target.jsonPropertyPath,
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
            if (!includeIncomplete && jsonPropertyPath.isBlank()) {
                return@mapNotNull null
            }
            McpServerSyncTarget(
                jsonFilePath = jsonFilePath,
                jsonPropertyPath = jsonPropertyPath,
                transportType = transport.name,
            )
        }
    }

    private fun chooseJsonPropertyPath(modelRow: Int, currentPath: String): String? {
        val jsonFilePath = (model.getValueAt(modelRow, FILE_COLUMN) as? String).orEmpty().trim()
        if (jsonFilePath.isBlank()) {
            Messages.showErrorDialog(table, "Select a JSON file before browsing properties.", "JSON Property Path")
            return null
        }

        val rootNode = runCatching { JsonPropertyPathChooserDialog.loadRoot(jsonFilePath) }
            .getOrElse { error ->
                Messages.showErrorDialog(
                    table,
                    error.message ?: "Could not read JSON file.",
                    "JSON Property Path",
                )
                return null
            }

        val dialog = JsonPropertyPathChooserDialog(table, rootNode, currentPath)
        return if (dialog.showAndGet()) dialog.selectedJsonPath() else null
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

    private class JsonPropertyPathCellEditor(
        private val choosePath: (modelRow: Int, currentPath: String) -> String?,
    ) : AbstractCellEditor(), TableCellEditor {
        private val textFieldWithBrowseButton = TextFieldWithBrowseButton()
        private var currentModelRow: Int = -1

        init {
            textFieldWithBrowseButton.addActionListener {
                if (currentModelRow < 0) {
                    return@addActionListener
                }
                choosePath(currentModelRow, textFieldWithBrowseButton.text)?.let { selectedPath ->
                    textFieldWithBrowseButton.text = selectedPath
                }
            }
        }

        override fun getTableCellEditorComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            row: Int,
            column: Int,
        ): Component {
            currentModelRow = table.convertRowIndexToModel(row)
            textFieldWithBrowseButton.text = value as? String ?: ""
            return textFieldWithBrowseButton
        }

        override fun getCellEditorValue(): Any = textFieldWithBrowseButton.text
    }

    private class JsonPropertyPathChooserDialog(
        parent: Component,
        private val rootNode: DefaultMutableTreeNode,
        initialPath: String,
    ) : DialogWrapper(parent, true) {
        private val tree = Tree(rootNode).apply {
            isRootVisible = true
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        }

        init {
            title = "Select JSON Property"
            init()
            expandAllRows()
            selectInitialPath(initialPath)
        }

        override fun createCenterPanel(): JComponent {
            val help = JBLabel("Select the JSON property that should receive the current IntelliJ MCP server URL.").apply {
                foreground = JBColor.GRAY
                border = JBUI.Borders.emptyBottom(8)
            }
            return BorderLayoutPanel().apply {
                border = JBUI.Borders.empty(8)
                preferredSize = Dimension(JBUI.scale(520), JBUI.scale(360))
                addToTop(help)
                addToCenter(JBScrollPane(tree))
            }
        }

        override fun doValidate(): ValidationInfo? {
            return if (selectedJsonPath() == null) {
                ValidationInfo("Select a JSON property.", tree)
            } else {
                null
            }
        }

        fun selectedJsonPath(): String? {
            val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
            return (node.userObject as? JsonPathNode)?.path
        }

        private fun expandAllRows() {
            var row = 0
            while (row < tree.rowCount) {
                tree.expandRow(row)
                row++
            }
        }

        private fun selectInitialPath(initialPath: String) {
            val normalized = initialPath.trim().takeIf { it.isNotBlank() }
            if (normalized != null && selectPath(normalized)) {
                return
            }
            val likelyPath = McpJsonTargetUpdater.findLikelyMcpServerPath(availablePaths()) ?: return
            selectPath(likelyPath)
        }

        private fun selectPath(pathToSelect: String): Boolean {
            val enumeration = rootNode.depthFirstEnumeration()
            while (enumeration.hasMoreElements()) {
                val node = enumeration.nextElement() as? DefaultMutableTreeNode ?: continue
                val path = (node.userObject as? JsonPathNode)?.path ?: continue
                if (path == pathToSelect) {
                    tree.selectionPath = TreePath(node.path)
                    tree.scrollPathToVisible(tree.selectionPath)
                    return true
                }
            }
            return false
        }

        private fun availablePaths(): List<String> {
            val paths = mutableListOf<String>()
            val enumeration = rootNode.depthFirstEnumeration()
            while (enumeration.hasMoreElements()) {
                val node = enumeration.nextElement() as? DefaultMutableTreeNode ?: continue
                val path = (node.userObject as? JsonPathNode)?.path ?: continue
                paths += path
            }
            return paths
        }

        companion object {
            private val json = Json {
                allowComments = true
                allowTrailingComma = true
            }

            fun loadRoot(jsonFilePath: String): DefaultMutableTreeNode {
                val file = McpJsonTargetUpdater.resolveJsonFilePath(jsonFilePath)
                require(Files.exists(file)) { "JSON file does not exist: $file" }

                val content = Files.readString(file, StandardCharsets.UTF_8)
                val rootElement = json.parseToJsonElement(content)
                val rootObject = rootElement as? JsonObject
                    ?: error("Top-level JSON value must be an object.")
                val rootLabel = file.fileName?.toString() ?: file.toString()
                return DefaultMutableTreeNode(JsonPathNode(rootLabel, null)).apply {
                    rootObject.forEach { (key, value) ->
                        add(createNode(key, listOf(key), value))
                    }
                }
            }

            private fun createNode(key: String, pathSegments: List<String>, value: JsonElement): DefaultMutableTreeNode {
                val path = McpJsonTargetUpdater.formatDotPath(pathSegments)
                return DefaultMutableTreeNode(JsonPathNode("$key (${typeLabel(value)})", path)).apply {
                    if (value is JsonObject) {
                        value.forEach { (childKey, childValue) ->
                            add(createNode(childKey, pathSegments + childKey, childValue))
                        }
                    }
                }
            }

            private fun typeLabel(value: JsonElement): String {
                return when (value) {
                    is JsonObject -> "object"
                    is JsonArray -> "array"
                    is JsonPrimitive -> if (value.isString) "string" else "value"
                }
            }
        }
    }

    private data class JsonPathNode(
        val label: String,
        val path: String?,
    ) {
        override fun toString(): String = label
    }

    companion object {
        private val COLUMNS = arrayOf("JSON file", "JSON property path", "Transport")
        private const val FILE_COLUMN = 0
        private const val PROPERTY_COLUMN = 1
        private const val TRANSPORT_COLUMN = 2
    }
}
