package de.moritzf.quota.idea.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import de.moritzf.quota.idea.action.PdfDocumentConversion
import de.moritzf.quota.idea.mcp.DocumentToMarkdownProvider
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.shared.DocumentModels
import de.moritzf.quota.shared.HelloPdf
import java.awt.Dimension
import java.awt.Font
import java.awt.Image
import java.awt.event.ActionEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.swing.Action
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

/** Runs the selected document model against a one-page PDF created by PDFBox. */
internal class DocumentTestButton(
    private val provider: DocumentToMarkdownProvider,
    private val selectedModel: () -> String,
    private val modality: () -> JComponent?,
    watch: JComboBox<*>? = null,
) : JButton("Test document") {
    init {
        toolTipText = "Convert a one-page hello PDF with the selected model"
        watch?.addItemListener { updateEnabled() }
        // Replacing the model can change the selection without firing an item event.
        watch?.addPropertyChangeListener("model") { updateEnabled() }
        updateEnabled()
        addActionListener { runTest() }
    }

    /** "-" turns conversion off, so there is nothing to run. */
    private fun updateEnabled() {
        isEnabled = selectedModel().trim().let { it.isNotEmpty() && it != DocumentModels.OFF }
    }

    private fun runTest() {
        DocumentTestDialog(modality() ?: this, provider, selectedModel).show()
    }
}

private class DocumentTestDialog(
    parent: JComponent,
    private val provider: DocumentToMarkdownProvider,
    private val selectedModel: () -> String,
) : DialogWrapper(parent, true) {
    private val task = TestDialogTask { isDisposed }
    private val statusIcon = JBLabel()
    private val statusLabel = JBLabel().apply { font = font.deriveFont(Font.BOLD) }
    private val modelLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val latencyLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val inputSlot = slot()
    private val outputSlot = slot()
    private val detailSlot = slot()
    private val abortAction =
        object : DialogWrapperAction("Abort") {
            override fun doAction(event: ActionEvent) {
                abort()
            }
        }
    private val retryAction =
        object : DialogWrapperAction("Retry") {
            override fun doAction(event: ActionEvent) {
                start()
            }
        }

    init {
        title = "Test document"
        setOKButtonText("Close")
        init()
        start()
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row {
                cell(statusIcon)
                cell(statusLabel)
                cell(modelLabel)
                cell(latencyLabel)
            }
            group("Input") { row { cell(inputSlot).resizableColumn().align(AlignX.FILL) } }
            group("Output") { row { cell(outputSlot).resizableColumn().align(AlignX.FILL) } }
            row { cell(detailSlot).resizableColumn().align(AlignX.FILL) }
        }
            .apply { preferredSize = Dimension(JBUI.scale(520), JBUI.scale(360)) }
    }

    override fun createActions(): Array<Action> = arrayOf(abortAction, retryAction, okAction)

    override fun dispose() {
        task.cancel()
        super.dispose()
    }

    private fun start() {
        val model = selectedModel()
        showRunning(model)
        task.start { gen -> runGeneration(gen, model) }
    }

    private fun abort() {
        task.cancel()
        showAborted()
    }

    private fun runGeneration(gen: Int, model: String) {
        val pdf = Files.createTempFile("quota-hello-", ".pdf")
        val output = Files.createTempFile("quota-hello-", ".md")
        var page: BufferedImage? = null
        try {
            checkActive(gen)
            HelloPdf.write(pdf)
            page = HelloPdf.renderPage(pdf)
            val rendered = page
            onEdt(gen) { showPage(rendered) }
            checkActive(gen)
            val started = System.nanoTime()
            val warnings =
                try {
                    PdfDocumentConversion.convert(
                            provider,
                            pdf,
                            output,
                            includeImages = false,
                            progress = { _, _, _ -> checkActive(gen) },
                            model = model,
                        )
                        .warnings
                } catch (exception: Exception) {
                    if (!isActive(gen) || isCancellation(exception)) throw exception
                    val elapsedMs = (System.nanoTime() - started) / 1_000_000L
                    onEdt(gen) {
                        showFailure(exception.message ?: "Document test failed", page, elapsedMs)
                    }
                    return@runGeneration
                }
            checkActive(gen)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L
            val markdown = Files.readString(output)
            onEdt(gen) {
                showSuccess(
                    rendered,
                    markdown,
                    warnings.joinToString("\n").ifBlank { null },
                    elapsedMs,
                )
            }
        } catch (exception: ProcessCanceledException) {
            if (isActive(gen)) throw exception
        } catch (exception: Exception) {
            if (!isActive(gen) || isCancellation(exception)) return
            val rendered = page
            onEdt(gen) { showFailure(exception.message ?: "Document test failed", rendered, null) }
        } finally {
            Files.deleteIfExists(pdf)
            Files.deleteIfExists(output)
        }
    }

    private fun showRunning(model: String) {
        statusIcon.icon = AnimatedIcon.Default.INSTANCE
        statusLabel.text = "Testing…"
        modelLabel.text = model
        modelLabel.isVisible = model.isNotBlank()
        showLatency(null)
        replace(inputSlot, note("Preparing the sample page…"))
        replace(outputSlot, note("Waiting for the model."))
        clear(detailSlot)
        abortAction.isEnabled = true
        retryAction.isEnabled = false
    }

    private fun showPage(image: BufferedImage) {
        replace(inputSlot, pagePreview(image))
    }

    private fun showSuccess(
        page: BufferedImage,
        markdown: String,
        detail: String?,
        elapsedMs: Long,
    ) {
        statusIcon.icon = AllIcons.General.InspectionsOK
        statusLabel.text = "Converted"
        showLatency(elapsedMs)
        showPage(page)
        replace(outputSlot, codeBlock(markdown))
        showDetail(detail)
        abortAction.isEnabled = false
        retryAction.isEnabled = true
    }

    private fun showFailure(message: String, page: BufferedImage?, elapsedMs: Long?) {
        statusIcon.icon = AllIcons.General.Error
        statusLabel.text = "Failed"
        showLatency(elapsedMs)
        if (page != null) showPage(page)
        replace(outputSlot, codeBlock(message))
        clear(detailSlot)
        abortAction.isEnabled = false
        retryAction.isEnabled = true
    }

    private fun showAborted() {
        statusIcon.icon = AllIcons.Actions.Cancel
        statusLabel.text = "Aborted"
        showLatency(null)
        replace(outputSlot, note("Test aborted."))
        abortAction.isEnabled = false
        retryAction.isEnabled = true
    }

    private fun showLatency(elapsedMs: Long?) {
        latencyLabel.text = elapsedMs?.let { "$it ms" }.orEmpty()
        latencyLabel.isVisible = elapsedMs != null
    }

    private fun showDetail(detail: String?) {
        if (detail.isNullOrBlank()) {
            clear(detailSlot)
            return
        }
        val html = QuotaUiUtil.escapeHtml(detail).replace("\n", "<br>")
        replace(detailSlot, JBLabel("<html><body style='width: 460px'>$html</body></html>"))
    }

    private fun checkActive(gen: Int) = task.checkActive(gen)

    private fun isActive(gen: Int) = task.isActive(gen)

    private fun isCancellation(failure: Throwable) = TestDialogTask.isCancellation(failure)

    private fun onEdt(gen: Int, update: () -> Unit) = task.onEdt(gen, update)

    private fun pagePreview(image: BufferedImage): JComponent {
        val maxWidth = JBUI.scale(480)
        val scale = minOf(1.0, maxWidth.toDouble() / image.width)
        val icon =
            ImageIcon(
                image.getScaledInstance(
                    (image.width * scale).toInt(),
                    (image.height * scale).toInt(),
                    Image.SCALE_SMOOTH,
                )
            )
        return JBScrollPane(JBLabel(icon)).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 1)
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            preferredSize =
                Dimension(maxWidth, icon.iconHeight.coerceAtMost(JBUI.scale(280)) + JBUI.scale(4))
        }
    }

    private fun codeBlock(text: String): JComponent = TestDialogUi.codeBlock(text)

    private fun note(text: String) = TestDialogUi.note(text)

    private fun replace(slot: JPanel, component: JComponent) = TestDialogUi.replace(slot, component)

    private fun clear(slot: JPanel) = TestDialogUi.clear(slot)

    private fun slot() = TestDialogUi.slot()
}
