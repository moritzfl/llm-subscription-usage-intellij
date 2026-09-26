package de.moritzf.quota.idea.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.AnimatedIcon
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
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.Image
import java.awt.event.ActionEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.Action
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities

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
        DocumentTestDialog(modality() ?: this, provider, selectedModel).show()
    }
}

private class DocumentTestDialog(
    parent: JComponent,
    private val provider: DocumentToMarkdownProvider,
    private val selectedModel: () -> String,
) : DialogWrapper(parent, true) {
    private val generation = AtomicInteger()
    private var worker: Thread? = null
    private val statusIcon = JBLabel()
    private val statusLabel = JBLabel().apply { font = font.deriveFont(Font.BOLD) }
    private val modelLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val latencyLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val inputSlot = slot()
    private val outputSlot = slot()
    private val detailSlot = slot()
    private val abortAction = object : DialogWrapperAction("Abort") {
        override fun doAction(event: ActionEvent) {
            abort()
        }
    }
    private val retryAction = object : DialogWrapperAction("Retry") {
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
            group("Input") {
                row {
                    cell(inputSlot)
                        .resizableColumn()
                        .align(AlignX.FILL)
                }
            }
            group("Output") {
                row {
                    cell(outputSlot)
                        .resizableColumn()
                        .align(AlignX.FILL)
                }
            }
            row {
                cell(detailSlot)
                    .resizableColumn()
                    .align(AlignX.FILL)
            }
        }.apply {
            preferredSize = Dimension(JBUI.scale(520), JBUI.scale(360))
        }
    }

    override fun createActions(): Array<Action> = arrayOf(abortAction, retryAction, okAction)

    override fun dispose() {
        generation.incrementAndGet()
        worker?.interrupt()
        worker = null
        super.dispose()
    }

    private fun start() {
        val model = selectedModel()
        val gen = generation.incrementAndGet()
        worker?.interrupt()
        showRunning(model)
        val thread = Thread({ runGeneration(gen, model) }, "document-test")
        thread.isDaemon = true
        worker = thread
        thread.start()
    }

    private fun abort() {
        generation.incrementAndGet()
        worker?.interrupt()
        worker = null
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
            val warnings = try {
                PdfDocumentConversion.convert(
                    provider, pdf, output, includeImages = false,
                    progress = { _, _, _ -> checkActive(gen) },
                    model = model,
                )
            } catch (exception: Exception) {
                if (!isActive(gen) || isCancellation(exception)) throw exception
                val elapsedMs = (System.nanoTime() - started) / 1_000_000L
                onEdt(gen) { showFailure(exception.message ?: "Document test failed", page, elapsedMs) }
                return@runGeneration
            }
            checkActive(gen)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L
            val markdown = Files.readString(output)
            onEdt(gen) {
                showSuccess(rendered, markdown, warnings.joinToString("\n").ifBlank { null }, elapsedMs)
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

    private fun showSuccess(page: BufferedImage, markdown: String, detail: String?, elapsedMs: Long) {
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

    private fun checkActive(gen: Int) {
        if (!isActive(gen) || Thread.currentThread().isInterrupted) throw CancellationException("Aborted")
    }

    private fun isActive(gen: Int) = generation.get() == gen && !isDisposed

    private fun isCancellation(exception: Throwable): Boolean {
        var current: Throwable? = exception
        while (current != null) {
            if (current is CancellationException || current is InterruptedException || current is ProcessCanceledException) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun onEdt(gen: Int, update: () -> Unit) {
        SwingUtilities.invokeLater {
            if (!isActive(gen)) return@invokeLater
            update()
        }
    }

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

    private fun note(text: String) = JBLabel(text).apply { foreground = UIUtil.getContextHelpForeground() }

    private fun replace(slot: JPanel, component: JComponent) {
        slot.removeAll()
        slot.add(component, BorderLayout.NORTH)
        slot.revalidate()
        slot.repaint()
    }

    private fun clear(slot: JPanel) {
        slot.removeAll()
        slot.revalidate()
        slot.repaint()
    }

    private fun slot() = JPanel(BorderLayout()).apply { isOpaque = false }
}
