package de.moritzf.quota.idea.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.dsl.builder.panel
import de.moritzf.quota.idea.action.PdfDocumentConversion
import de.moritzf.quota.idea.mcp.DocumentToMarkdownProvider
import de.moritzf.quota.shared.HelloPdf
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JComponent

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
                val warnings = PdfDocumentConversion.convert(
                    provider, pdf, output, includeImages = false, model = selected,
                )
                val markdown = Files.readString(output)
                DocumentTestResult(true, "Converted", markdown, warnings.joinToString("\n").ifBlank { null })
            } catch (exception: ProcessCanceledException) {
                throw exception
            } catch (exception: Exception) {
                DocumentTestResult(false, exception.message ?: "Document test failed", null, null)
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

    override fun createCenterPanel(): JComponent = panel {
        row { label(result.status).bold() }
        result.markdown?.take(1200)?.let { sample ->
            row { comment(sample) }
        }
        result.detail?.let { row { comment(it) } }
        row { comment("Sample PDF text: ${HelloPdf.TEXT}") }
    }
}
