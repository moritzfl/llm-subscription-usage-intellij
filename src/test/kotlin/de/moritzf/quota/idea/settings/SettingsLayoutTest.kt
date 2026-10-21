package de.moritzf.quota.idea.settings

import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.components.BorderLayoutPanel
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsLayoutTest {
    private val help =
        "Model for PDF-to-Markdown conversion (subscription_document_to_markdown). Select - to " +
            "turn it off for this account. Local PDFBox text extraction remains available."

    @Test
    fun lastRowStaysVisibleOnceWrappedTextKnowsItsWidth() {
        lateinit var scroll: JScrollPane
        lateinit var form: JComponent
        val status =
            JLabel(statusDotHtml("AI Completion looks configured.", AuthStatusKind.CONNECTED))
        SwingUtilities.invokeAndWait {
            form = panel {
                repeat(6) { row { comment(help) } }
                row { cell(status).align(AlignX.FILL).resizableColumn() }
            }
            val content =
                BorderLayoutPanel().apply {
                    addToTop(form)
                    addToCenter(JPanel().apply { preferredSize = Dimension(1, 180) })
                }
            scroll = verticalScrollPane(content)
            scroll.setSize(320, 200)
            scroll.addNotify()
            scroll.validate()
        }
        // The first layout gives wrapped text its width; the settle pass runs on the next turn.
        SwingUtilities.invokeAndWait {
            try {
                assertEquals(form.preferredSize.height, form.height, "Form height is stale")
                assertTrue(status.y + status.height <= form.height, "Last row is clipped")
            } finally {
                scroll.removeNotify()
            }
        }
    }
}
