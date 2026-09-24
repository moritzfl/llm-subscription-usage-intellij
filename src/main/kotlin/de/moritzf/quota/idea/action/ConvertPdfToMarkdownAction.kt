package de.moritzf.quota.idea.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import de.moritzf.quota.idea.mcp.DocumentToMarkdownProvider
import de.moritzf.quota.shared.DocumentMarkdown
import java.awt.Dimension
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent

/** Shown for one local PDF in the Project View or an editor's context menu. */
class ConvertPdfToMarkdownAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null && pdfFromSelection(
            event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList(),
            event.getData(CommonDataKeys.VIRTUAL_FILE),
        ) != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val pdf = pdfFromSelection(
            event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList(),
            event.getData(CommonDataKeys.VIRTUAL_FILE),
        ) ?: return
        val source = Path.of(pdf.path)
        ApplicationManager.getApplication().executeOnPooledThread {
            val providers = PdfDocumentConversion.availableProviders()
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                if (providers.isEmpty()) {
                    Messages.showInfoMessage(project, "Configure a document-conversion provider in LLM Subscription Usage settings.", "PDF to Markdown")
                    return@invokeLater
                }
                val dialog = ConvertPdfToMarkdownDialog(project, source, providers)
                if (!dialog.showAndGet()) return@invokeLater
                val destination = dialog.outputFile()
                if (Files.exists(destination) && Messages.showYesNoDialog(
                        project, "Overwrite ${destination.fileName}?", "PDF to Markdown", Messages.getQuestionIcon(),
                    ) != Messages.YES) return@invokeLater
                ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Converting PDF to Markdown", false) {
                    override fun run(indicator: ProgressIndicator) {
                        indicator.isIndeterminate = true
                        PdfDocumentConversion.convert(dialog.provider(), source, destination, dialog.includeImages())
                    }

                    override fun onSuccess() {
                        if (project.isDisposed) return
                        val result = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(destination)
                        if (result == null) {
                            Messages.showErrorDialog(project, "Markdown file was not found after conversion.", "PDF to Markdown")
                        } else {
                            FileEditorManager.getInstance(project).openFile(result, true)
                        }
                    }

                    override fun onThrowable(error: Throwable) {
                        if (!project.isDisposed) Messages.showErrorDialog(
                            project, error.message ?: "Document conversion failed.", "PDF to Markdown",
                        )
                    }
                })
            }
        }
    }
}

internal fun pdfFromSelection(files: List<VirtualFile>?, single: VirtualFile?): VirtualFile? {
    val file = if (files.isNullOrEmpty()) single else files.singleOrNull()
    return file?.takeIf { !it.isDirectory && it.isInLocalFileSystem && it.extension.equals("pdf", ignoreCase = true) }
}

private class ConvertPdfToMarkdownDialog(
    private val project: Project,
    private val source: Path,
    providers: List<DocumentToMarkdownProvider>,
) : DialogWrapper(project) {
    private val defaultFileName = checkNotNull(DocumentMarkdown.defaultOutput(source)).fileName
    private val providerCombo = ComboBox(providers.toTypedArray()).apply {
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: javax.swing.JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
            ): java.awt.Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                text = when (value) {
                    DocumentToMarkdownProvider.MISTRAL -> "Mistral OCR"
                    DocumentToMarkdownProvider.AZURE -> "Azure document model"
                    DocumentToMarkdownProvider.ZAI -> "Z.ai GLM-OCR"
                    DocumentToMarkdownProvider.OPEN_AI -> "OpenAI/Codex vision"
                    DocumentToMarkdownProvider.SUPERGROK -> "SuperGrok vision"
                    else -> ""
                }
                return this
            }
        }
    }
    private val imagesCheckBox = JBCheckBox("Save detected figures", true)
    private val outputField = TextFieldWithBrowseButton().apply {
        textField.columns = 40
        text = defaultFileName.toString()
        addActionListener {
            val parent = LocalFileSystem.getInstance().findFileByNioFile(source.parent)
            val folder = FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFolderDescriptor(), project, parent)
            if (folder != null) {
                val name = runCatching { Path.of(text.trim()).fileName }.getOrNull()
                    ?.takeIf { it.toString().endsWith(".md", ignoreCase = true) } ?: defaultFileName
                text = Path.of(folder.path).resolve(name).toString()
            }
        }
    }

    init {
        title = "Convert PDF to Markdown"
        setOKButtonText("Convert")
        isResizable = true
        init()
        window.minimumSize = window.preferredSize
    }

    override fun createCenterPanel(): JComponent = panel {
        row("PDF:") {
            cell(JBLabel(source.fileName.toString()).apply {
                toolTipText = source.toString()
                // Ellipsize long names instead of widening the shared controls column.
                preferredSize = Dimension(minOf(preferredSize.width, JBUI.scale(520)), preferredSize.height)
                minimumSize = Dimension(0, minimumSize.height)
            })
                .align(AlignX.FILL).resizableColumn()
        }
        row("Provider:") { cell(providerCombo).align(AlignX.FILL).resizableColumn() }
        row("Images:") { cell(imagesCheckBox) }
        row("Output file:") {
            cell(outputField).align(AlignX.FILL).resizableColumn()
                .comment("Relative paths are saved beside the PDF. Browse to choose another folder.")
        }
    }.apply {
        // Never pack the dialog narrower than the layout needs (including arrow/browse buttons).
        preferredSize = Dimension(maxOf(JBUI.scale(640), preferredSize.width, minimumSize.width), preferredSize.height)
    }

    override fun doValidate(): ValidationInfo? {
        val path = try { outputFile() } catch (_: InvalidPathException) {
            return ValidationInfo("Enter a valid Markdown output path.", outputField)
        }
        return if (path.fileName.toString().endsWith(".md", ignoreCase = true)) null
        else ValidationInfo("Output file must end in .md.", outputField)
    }

    fun provider(): DocumentToMarkdownProvider = providerCombo.selectedItem as DocumentToMarkdownProvider
    fun includeImages(): Boolean = imagesCheckBox.isSelected
    fun outputFile(): Path = resolveMarkdownOutput(source, outputField.text)
}

internal fun resolveMarkdownOutput(source: Path, value: String): Path {
    val selected = Path.of(value.trim())
    return (if (selected.isAbsolute) selected else source.toAbsolutePath().parent.resolve(selected)).normalize()
}
