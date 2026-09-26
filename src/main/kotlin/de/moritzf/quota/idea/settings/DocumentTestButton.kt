package de.moritzf.quota.idea.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import de.moritzf.quota.idea.action.PdfDocumentConversion
import de.moritzf.quota.idea.mcp.DocumentToMarkdownProvider
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.shared.HelloPdf
import java.awt.Dimension
import java.awt.Font
import java.awt.Image
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.swing.Action
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.ScrollPaneConstants

/** Runs the selected document model against a one-page PDF created by PDFBox. */
internal class DocumentTestButton(
    private val provider: DocumentToMarkdownProvider,
    private val selectedModel: () -> String,
    private val modality: () -> JComponent?,
) : JButton("Test document") {
    init {
        toolTipText = "Convert a one-page hello PDF with the selected model"
        addActionListener { runTest() }
    }

    private fun runTest() {
        isEnabled = false
        val selected = selectedModel()
        ApplicationManager.getApplication().executeOnPooledThread {
            val pdf = Files.createTempFile("quota-hello-", ".pdf")
            val output = Files.createTempFile("quota-hello-", ".md")
            val result = try {
                HelloPdf.write(pdf)
                val page = HelloPdf.renderPage(pdf)
                val warnings = PdfDocumentConversion.convert(
                    provider, pdf, output, includeImages = false, model = selected,
                )
                val markdown = Files.readString(output)
                DocumentTestResult(
                    true,
                    "Converted",
                    selected,
                    page,
                    markdown,
                    warnings.joinToString("\n").ifBlank { null },
                )
            } catch (exception: ProcessCanceledException) {
                throw exception
            } catch (exception: Exception) {
                DocumentTestResult(
                    false,
                    exception.message ?: "Document test failed",
                    selected,
                    runCatching { HelloPdf.renderPage(pdf) }.getOrNull(),
                    null,
                    null,
                )
            } finally {
                Files.deleteIfExists(pdf)
                Files.deleteIfExists(output)
            }
            ApplicationManager.getApplication().invokeLater({
                isEnabled = true
                val parent = modality()
                if (parent == null) {
                    Messages.showInfoMessage(result.status, "Test document")
                } else {
                    DocumentTestResultDialog(parent, result).show()
                }
            }, ModalityState.stateForComponent(modality() ?: this))
        }
    }
}

internal data class DocumentTestResult(
    val ok: Boolean,
    val status: String,
    val model: String,
    val page: BufferedImage?,
    val markdown: String?,
    val detail: String?,
)

private class DocumentTestResultDialog(
    parent: JComponent,
    private val result: DocumentTestResult,
) : DialogWrapper(parent, true) {
    init {
        title = "Test document"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val icon = if (result.ok) AllIcons.General.InspectionsOK else AllIcons.General.Error
        return panel {
            row {
                icon(icon)
                label(result.status).bold()
                if (result.model.isNotBlank()) comment(result.model)
            }
            result.page?.let { page ->
                group("Input") {
                    row {
                        cell(pagePreview(page))
                            .resizableColumn()
                            .align(AlignX.FILL)
                    }
                }
            }
            result.markdown?.let { markdown ->
                group("Output") {
                    row {
                        cell(codeBlock(markdown))
                            .resizableColumn()
                            .align(AlignX.FILL)
                    }
                }
            }
            result.detail?.let { detail ->
                row {
                    text(QuotaUiUtil.escapeHtml(detail).replace("\n", "<br>"))
                        .resizableColumn()
                        .align(AlignX.FILL)
                }
            }
        }.apply {
            preferredSize = Dimension(JBUI.scale(520), JBUI.scale(360))
        }
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    private fun pagePreview(image: BufferedImage): JComponent {
        val maxWidth = JBUI.scale(480)
        val scale = minOf(1.0, maxWidth.toDouble() / image.width)
        val icon = ImageIcon(image.getScaledInstance((image.width * scale).toInt(), (image.height * scale).toInt(), Image.SCALE_SMOOTH))
        return JBScrollPane(JBLabel(icon)).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 1)
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            preferredSize = Dimension(maxWidth, icon.iconHeight.coerceAtMost(JBUI.scale(280)) + JBUI.scale(4))
        }
    }

    private fun codeBlock(text: String): JComponent {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val area = JBTextArea(text).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = Font(scheme.editorFontName, Font.PLAIN, scheme.editorFontSize)
            background = UIUtil.getTextFieldBackground()
            border = JBUI.Borders.empty(8)
        }
        return JBScrollPane(area).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 1)
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            preferredSize = Dimension(JBUI.scale(480), JBUI.scale(96))
        }
    }
}
