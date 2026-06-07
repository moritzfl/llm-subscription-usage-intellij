package de.moritzf.quota.idea.settings

import com.intellij.openapi.actionSystem.ActionToolbarPosition
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.quota.idea.mcp.McpJsonTargetUpdater
import de.moritzf.quota.idea.mcp.McpJsonTargetValidationProblem
import de.moritzf.quota.idea.mcp.McpServerSyncTarget
import de.moritzf.quota.idea.mcp.McpServerTransport
import de.moritzf.quota.idea.mcp.McpTomlTargetUpdater
import de.moritzf.quota.idea.mcp.McpYamlTargetUpdater
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.awt.Component
import java.awt.Dimension
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import javax.swing.AbstractCellEditor
import javax.swing.DefaultCellEditor
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer
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
        columnModel.getColumn(FILE_COLUMN).cellRenderer = ValidationCellRenderer(FILE_COLUMN)
        columnModel.getColumn(PROPERTY_COLUMN).preferredWidth = JBUI.scale(300)
        columnModel.getColumn(PROPERTY_COLUMN).cellEditor = PropertyPathCellEditor(::choosePropertyPath)
        columnModel.getColumn(PROPERTY_COLUMN).cellRenderer = ValidationCellRenderer(PROPERTY_COLUMN)
        columnModel.getColumn(TRANSPORT_COLUMN).preferredWidth = JBUI.scale(140)
        columnModel.getColumn(TRANSPORT_COLUMN).cellEditor = DefaultCellEditor(
            JComboBox(McpServerTransport.entries.toTypedArray()),
        )
    }
    private var rowValidations: Map<Int, RowValidation> = emptyMap()

    init {
        title = "IntelliJ MCP Server URL Sync Targets"
        model.addTableModelListener { refreshValidationState() }
        initialTargets.forEach { addTarget(it) }
        refreshValidationState()
        init()
    }

    fun targets(): List<McpServerSyncTarget> = readTargets()

    override fun createCenterPanel(): JComponent {
        val help = JBLabel(
            "Use dot paths like mcpServers.jetbrains.url for JSON/TOML/YAML, or JSON Pointer paths like /mcpServers/jetbrains/url.",
        ).apply {
            foreground = JBColor.GRAY
            border = JBUI.Borders.emptyBottom(8)
        }

        val tablePanel = ToolbarDecorator.createDecorator(table)
            .setAddAction { addTarget() }
            .setRemoveAction { removeSelectedTargets() }
            .disableUpDownActions()
            .setToolbarPosition(ActionToolbarPosition.BOTTOM)
            .createPanel()

        return BorderLayoutPanel().apply {
            border = JBUI.Borders.empty(8)
            addToTop(help)
            addToCenter(tablePanel)
        }
    }

    override fun doValidate(): ValidationInfo? {
        table.cellEditor?.stopCellEditing()
        refreshValidationState()
        (0 until model.rowCount).forEach { row ->
            val error = rowValidations[row]?.firstError ?: return@forEach
            return ValidationInfo("Row ${row + 1}: $error", table)
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
        val selectedRows = table.selectedRows
        if (selectedRows.isEmpty()) {
            return
        }

        table.cellEditor?.cancelCellEditing()
        table.clearSelection()
        selectedRows
            .map(table::convertRowIndexToModel)
            .sortedDescending()
            .forEach(model::removeRow)
        table.requestFocusInWindow()
    }

    private fun readTargets(): List<McpServerSyncTarget> {
        table.cellEditor?.stopCellEditing()
        return (0 until model.rowCount).mapNotNull { row ->
            val target = readTargetAt(row)
            if (target.jsonPropertyPath.isBlank()) {
                return@mapNotNull null
            }
            target
        }
    }

    private fun readTargetAt(row: Int): McpServerSyncTarget {
        val jsonFilePath = (model.getValueAt(row, FILE_COLUMN) as? String).orEmpty().trim()
        val jsonPropertyPath = (model.getValueAt(row, PROPERTY_COLUMN) as? String).orEmpty().trim()
        val transport = when (val value = model.getValueAt(row, TRANSPORT_COLUMN)) {
            is McpServerTransport -> value
            is String -> McpServerTransport.fromStorageValue(value)
            else -> McpServerTransport.SSE
        }
        return McpServerSyncTarget(
            jsonFilePath = jsonFilePath,
            jsonPropertyPath = jsonPropertyPath,
            transportType = transport.name,
        )
    }

    private fun refreshValidationState() {
        rowValidations = (0 until model.rowCount).associateWith(::validateRow)
        table.repaint()
    }

    private fun validateRow(row: Int): RowValidation {
        val target = readTargetAt(row)
        if (target.jsonPropertyPath.isBlank()) {
            return RowValidation()
        }
        if (target.jsonFilePath.isBlank()) {
            return RowValidation(fileError = "JSON/TOML/YAML file path is required.")
        }
        val error = McpJsonTargetUpdater.validateTargetFile(target.jsonFilePath, target.jsonPropertyPath)
            ?: return RowValidation()
        return when (error.problem) {
            McpJsonTargetValidationProblem.FILE -> RowValidation(fileError = error.message)
            McpJsonTargetValidationProblem.PROPERTY -> RowValidation(propertyError = error.message)
        }
    }

    private fun validationErrorForCell(modelRow: Int, modelColumn: Int): String? {
        val validation = rowValidations[modelRow] ?: return null
        return when (modelColumn) {
            FILE_COLUMN -> validation.fileError
            PROPERTY_COLUMN -> validation.propertyError
            else -> null
        }
    }

    private inner class ValidationCellRenderer(private val modelColumn: Int) : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val error = validationErrorForCell(table.convertRowIndexToModel(row), modelColumn)
            toolTipText = error
            if (error != null) {
                component.foreground = JBColor.RED
            }
            return component
        }
    }

    private fun choosePropertyPath(modelRow: Int, currentPath: String): String? {
        val jsonFilePath = (model.getValueAt(modelRow, FILE_COLUMN) as? String).orEmpty().trim()
        if (jsonFilePath.isBlank()) {
            Messages.showErrorDialog(table, "Select a JSON, TOML, or YAML file before browsing properties.", "Property Path")
            return null
        }

        val rootNode = runCatching { PropertyPathChooserDialog.loadRoot(jsonFilePath) }
            .getOrElse { error ->
                Messages.showErrorDialog(
                    table,
                    error.message ?: "Could not read target file.",
                    "Property Path",
                )
                return null
            }

        val dialog = PropertyPathChooserDialog(table, rootNode, currentPath)
        return if (dialog.showAndGet()) dialog.selectedPath() else null
    }

    private class JsonFileCellEditor : AbstractCellEditor(), TableCellEditor {
        private val textFieldWithBrowseButton = TextFieldWithBrowseButton()

        init {
            val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
                .withTitle("Select JSON/TOML/YAML File")
                .withDescription("Choose the JSON, TOML, or YAML configuration file to update.")
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

    private class PropertyPathCellEditor(
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

    private class PropertyPathChooserDialog(
        parent: Component,
        private val rootNode: DefaultMutableTreeNode,
        initialPath: String,
    ) : DialogWrapper(parent, true) {
        private val tree = Tree(rootNode).apply {
            isRootVisible = true
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        }

        init {
            title = "Select Property"
            init()
            expandAllRows()
            selectInitialPath(initialPath)
        }

        override fun createCenterPanel(): JComponent {
            val help = JBLabel("Select the string property that should receive the current IntelliJ MCP server URL.").apply {
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
            val selectedNode = selectedNode() ?: return ValidationInfo("Select a property.", tree)
            if (selectedNode.path == null) {
                return ValidationInfo("Select a property.", tree)
            }
            return if (selectedNode.canUpdate) null else ValidationInfo("Select a string property.", tree)
        }

        fun selectedPath(): String? {
            return selectedNode()
                ?.takeIf { it.canUpdate }
                ?.path
        }

        private fun selectedNode(): PropertyPathNode? {
            val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
            return node.userObject as? PropertyPathNode
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
            if (normalized != null) {
                if (selectPath(normalized)) {
                    return
                }
                val normalizedSegments = runCatching { McpJsonTargetUpdater.parsePropertyPath(normalized) }.getOrNull()
                if (normalizedSegments != null && selectPathBySegments(normalizedSegments)) {
                    return
                }
            }
            val likelyPath = McpJsonTargetUpdater.findLikelyMcpServerPath(availablePaths()) ?: return
            selectPath(likelyPath)
        }

        private fun selectPath(pathToSelect: String): Boolean {
            val enumeration = rootNode.depthFirstEnumeration()
            while (enumeration.hasMoreElements()) {
                val node = enumeration.nextElement() as? DefaultMutableTreeNode ?: continue
                val path = (node.userObject as? PropertyPathNode)?.takeIf { it.canUpdate }?.path ?: continue
                if (path == pathToSelect) {
                    tree.selectionPath = TreePath(node.path)
                    tree.scrollPathToVisible(tree.selectionPath)
                    return true
                }
            }
            return false
        }

        private fun selectPathBySegments(pathSegments: List<String>): Boolean {
            val enumeration = rootNode.depthFirstEnumeration()
            while (enumeration.hasMoreElements()) {
                val node = enumeration.nextElement() as? DefaultMutableTreeNode ?: continue
                val path = (node.userObject as? PropertyPathNode)?.takeIf { it.canUpdate }?.path ?: continue
                val segments = runCatching { McpJsonTargetUpdater.parsePropertyPath(path) }.getOrNull() ?: continue
                if (segments == pathSegments) {
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
                val path = (node.userObject as? PropertyPathNode)?.takeIf { it.canUpdate }?.path ?: continue
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
                require(Files.exists(file)) { "Target file does not exist: $file" }

                val content = Files.readString(file, StandardCharsets.UTF_8)
                if (file.extensionEquals("toml")) {
                    return loadSimplePathsRoot(file.fileName?.toString() ?: file.toString(), McpTomlTargetUpdater.collectStringPropertyPaths(content))
                }
                if (file.extensionEquals("yaml") || file.extensionEquals("yml")) {
                    return loadSimplePathsRoot(file.fileName?.toString() ?: file.toString(), McpYamlTargetUpdater.collectStringPropertyPaths(content))
                }

                val rootElement = json.parseToJsonElement(content)
                val rootObject = rootElement as? JsonObject
                    ?: error("Top-level JSON value must be an object.")
                val rootLabel = file.fileName?.toString() ?: file.toString()
                return DefaultMutableTreeNode(PropertyPathNode(rootLabel, null, canUpdate = false)).apply {
                    rootObject.forEach { (key, value) ->
                        add(createNode(key, listOf(key), value))
                    }
                }
            }

            private fun loadSimplePathsRoot(rootLabel: String, paths: List<String>): DefaultMutableTreeNode {
                return DefaultMutableTreeNode(PropertyPathNode(rootLabel, null, canUpdate = false)).apply {
                    paths.sorted().forEach { path -> addPathNode(this, McpJsonTargetUpdater.parsePropertyPath(path)) }
                }
            }

            private fun addPathNode(root: DefaultMutableTreeNode, pathSegments: List<String>) {
                var current = root
                pathSegments.forEachIndexed { index, segment ->
                    val path = McpJsonTargetUpdater.formatDotPath(pathSegments.take(index + 1))
                    val canUpdate = index == pathSegments.lastIndex
                    val existing = findChild(current, path)
                    if (existing != null) {
                        current = existing
                    } else {
                        val node = DefaultMutableTreeNode(
                            PropertyPathNode(if (canUpdate) "$segment (string)" else "$segment (object)", path, canUpdate),
                        )
                        current.add(node)
                        current = node
                    }
                }
            }

            private fun findChild(parent: DefaultMutableTreeNode, path: String): DefaultMutableTreeNode? {
                for (index in 0 until parent.childCount) {
                    val child = parent.getChildAt(index) as? DefaultMutableTreeNode ?: continue
                    if ((child.userObject as? PropertyPathNode)?.path == path) {
                        return child
                    }
                }
                return null
            }

            private fun createNode(key: String, pathSegments: List<String>, value: JsonElement): DefaultMutableTreeNode {
                val path = McpJsonTargetUpdater.formatDotPath(pathSegments)
                return DefaultMutableTreeNode(
                    PropertyPathNode(nodeLabel(key, value), path, McpJsonTargetUpdater.isSupportedTargetValue(value)),
                ).apply {
                    if (value is JsonObject) {
                        value.forEach { (childKey, childValue) ->
                            add(createNode(childKey, pathSegments + childKey, childValue))
                        }
                    }
                }
            }

            private fun nodeLabel(key: String, value: JsonElement): String {
                val baseLabel = "$key (${typeLabel(value)})"
                return if (value is JsonObject) baseLabel else "$baseLabel: ${previewValue(value)}"
            }

            private fun typeLabel(value: JsonElement): String {
                return when (value) {
                    is JsonObject -> "object"
                    is JsonArray -> "array"
                    is JsonNull -> "null"
                    is JsonPrimitive -> if (value.isString) "string" else "value"
                }
            }

            private fun previewValue(value: JsonElement): String {
                val preview = value.toString().replace(Regex("\\s+"), " ")
                return if (preview.length <= VALUE_PREVIEW_LENGTH) {
                    preview
                } else {
                    preview.take(VALUE_PREVIEW_LENGTH - 3) + "..."
                }
            }

            private const val VALUE_PREVIEW_LENGTH = 120

            private fun java.nio.file.Path.extensionEquals(extension: String): Boolean {
                return fileName?.toString()?.substringAfterLast('.', missingDelimiterValue = "")
                    ?.equals(extension, ignoreCase = true) == true
            }
        }
    }

    private data class PropertyPathNode(
        val label: String,
        val path: String?,
        val canUpdate: Boolean,
    ) {
        override fun toString(): String = label
    }

    private data class RowValidation(
        val fileError: String? = null,
        val propertyError: String? = null,
    ) {
        val firstError: String?
            get() = fileError ?: propertyError
    }

    companion object {
        private val COLUMNS = arrayOf("JSON/TOML/YAML file", "Property path", "Transport")
        private const val FILE_COLUMN = 0
        private const val PROPERTY_COLUMN = 1
        private const val TRANSPORT_COLUMN = 2
    }
}
