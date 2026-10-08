package de.moritzf.quota.idea.action

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.shared.DocumentImageFormat
import java.awt.Dimension
import java.awt.Font
import javax.swing.Action
import javax.swing.JComponent

/** Keep the outcome readable; full per-image logs and stack traces remain selectable in Details. */
internal class DocumentConversionResultDialog(
    project: Project?,
    private val summary: String,
    private val details: String,
) : DialogWrapper(project) {
    init {
        title = "PDF to Markdown"
        setOKButtonText("Close")
        init()
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun createCenterPanel(): JComponent = panel {
        row {
            cell(
                JBLabel(
                    "<html><body style='width: 460px'>${
                        QuotaUiUtil.escapeHtml(summary).replace("\n", "<br>")
                    }</body></html>"
                )
            )
                .align(Align.FILL)
        }
        collapsibleGroup("Details") {
            row {
                cell(JBScrollPane(JBTextArea(details).apply {
                    isEditable = false
                    font = Font(Font.MONOSPACED, Font.PLAIN, UIUtil.getLabelFont().size)
                    caretPosition = 0
                }).apply {
                    preferredSize = Dimension(JBUI.scale(620), JBUI.scale(280))
                }).align(Align.FILL)
            }.resizableRow()
        }.apply {
            expanded = false
            packWindowHeight = true
        }
    }
}

internal fun documentConversionSummary(result: DocumentConversionResult): String {
    val images = result.imageExport ?: return "Markdown saved with warnings. Expand Details for more information."
    return buildString {
        append("Markdown saved.\nImages: ${images.succeeded} of ${images.total} saved; ${images.failed} failed.\n\n")
        when (images.requestedFormat) {
            DocumentImageFormat.SVG -> {
                append("Requested SVG: ${images.svg} saved.\n")
                append("Fallbacks used: ${images.png} PNG at ${images.dpi} DPI; ${images.provider} provider images.")
            }

            DocumentImageFormat.PNG -> {
                append("Requested PNG at ${images.dpi} DPI: ${images.png} saved.\n")
                append("Fallbacks used: ${images.provider} provider images.")
            }

            DocumentImageFormat.PROVIDER -> append("Provider images: ${images.provider} saved.")
        }
        if (images.failed > 0) append("\nFailed images were omitted from Markdown.")
    }
}

internal fun documentConversionDetails(result: DocumentConversionResult): String = buildString {
    if (result.warnings.isNotEmpty()) {
        appendLine("Warnings")
        appendLine(result.warnings.joinToString("\n"))
    }
    result.imageExport?.let { images ->
        appendLine("\nImage export log (requested ${images.requestedFormat}, ${images.dpi} DPI)")
        append(images.diagnostics.joinToString("\n\n"))
    }
}.trim()
