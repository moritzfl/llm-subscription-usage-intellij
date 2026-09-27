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
import de.moritzf.quota.idea.action.VisionImageAnalysis
import de.moritzf.quota.idea.mcp.VisionProvider
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
import javax.imageio.ImageIO
import javax.swing.Action
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities

/** Runs the selected vision model against the settings sample page rendered as a PNG. */
internal class VisionTestButton(
    private val provider: VisionProvider,
    private val selectedModel: () -> String,
    private val modality: () -> JComponent?,
) : JButton("Test vision") {
    init {
        toolTipText = "Ask the selected vision model to describe the sample page"
        addActionListener { VisionTestDialog(modality() ?: this, provider, selectedModel).show() }
    }
}

private class VisionTestDialog(
    parent: JComponent,
    private val provider: VisionProvider,
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
        title = "Test vision"
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
            group("Answer") {
                row {
                    cell(outputSlot)
                        .resizableColumn()
                        .align(AlignX.FILL)
                }
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
        val thread = Thread({ runGeneration(gen, model) }, "vision-test")
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
        val image = Files.createTempFile("quota-hello-", ".png")
        var page: BufferedImage? = null
        try {
            checkActive(gen)
            HelloPdf.write(pdf)
            page = HelloPdf.renderPage(pdf)
            val rendered = page
            ImageIO.write(rendered, "png", image.toFile())
            onEdt(gen) { showPage(rendered) }
            checkActive(gen)
            val started = System.nanoTime()
            val answer = try {
                VisionImageAnalysis.analyze(provider, image, TEST_PROMPT, model)
            } catch (exception: Exception) {
                if (!isActive(gen) || isCancellation(exception)) throw exception
                val elapsedMs = (System.nanoTime() - started) / 1_000_000L
                onEdt(gen) { showFailure(exception.message ?: "Vision test failed", page, elapsedMs) }
                return@runGeneration
            }
            checkActive(gen)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L
            onEdt(gen) { showSuccess(rendered, answer, elapsedMs) }
        } catch (exception: ProcessCanceledException) {
            if (isActive(gen)) throw exception
        } catch (exception: Exception) {
            if (!isActive(gen) || isCancellation(exception)) return
            val rendered = page
            onEdt(gen) { showFailure(exception.message ?: "Vision test failed", rendered, null) }
        } finally {
            Files.deleteIfExists(pdf)
            Files.deleteIfExists(image)
        }
    }

    private fun showRunning(model: String) {
        statusIcon.icon = AnimatedIcon.Default.INSTANCE
        statusLabel.text = "Testing…"
        modelLabel.text = model
        modelLabel.isVisible = model.isNotBlank()
        showLatency(null)
        replace(inputSlot, note("Rendering the sample page…"))
        replace(outputSlot, note("Waiting for the model."))
        abortAction.isEnabled = true
        retryAction.isEnabled = false
    }

    private fun showPage(image: BufferedImage) {
        replace(inputSlot, pagePreview(image))
    }

    private fun showSuccess(page: BufferedImage, answer: String, elapsedMs: Long) {
        statusIcon.icon = AllIcons.General.InspectionsOK
        statusLabel.text = "Answered"
        showLatency(elapsedMs)
        showPage(page)
        replace(outputSlot, codeBlock(answer))
        abortAction.isEnabled = false
        retryAction.isEnabled = true
    }

    private fun showFailure(message: String, page: BufferedImage?, elapsedMs: Long?) {
        statusIcon.icon = AllIcons.General.Error
        statusLabel.text = "Failed"
        showLatency(elapsedMs)
        if (page != null) showPage(page)
        replace(outputSlot, codeBlock(message))
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

    private fun slot() = JPanel(BorderLayout()).apply { isOpaque = false }

    private fun replace(slot: JPanel, component: JComponent) {
        slot.removeAll()
        slot.add(component, BorderLayout.NORTH)
        slot.revalidate()
        slot.repaint()
    }

    private companion object {
        const val TEST_PROMPT = "Describe this image. List the title and the table rows."
    }
}
