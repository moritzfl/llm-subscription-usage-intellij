package de.moritzf.quota.idea.settings

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
    fun wrappedStatusLineFitsInsideTheForm() = SwingUtilities.invokeAndWait {
        val status =
            JLabel(
                statusDotHtml(
                    "AI Completion looks configured (proxy URL and qwen2.5-coder). Type in the " +
                        "editor and wait for gray suggestions.",
                    AuthStatusKind.CONNECTED,
                )
            )
        val form = panel {
            row { button("Test AI Completion") {} }
            row { statusCell(status) }
        }
        val scroll =
            verticalScrollPane(
                BorderLayoutPanel().apply {
                    addToTop(form)
                    addToCenter(JPanel().apply { preferredSize = Dimension(1, 180) })
                }
            )
        val lineHeight = status.getFontMetrics(status.font).height
        for (width in listOf(260, 420, 900)) {
            scroll.setSize(width, 120)
            // An HTML label learns its wrapped height while painting, then asks for a new layout.
            repeat(3) {
                layoutHeadless(scroll, passes = 1)
                paint(scroll)
            }
            layoutHeadless(scroll, passes = 1)
            assertTrue(
                status.y + status.height <= form.height,
                "Status clipped at width $width: ${status.bounds} in ${form.size}",
            )
            if (width == 260) assertTrue(status.height > lineHeight, "Status must wrap")
        }
    }

    private fun paint(component: JComponent) {
        val image =
            java.awt.image.BufferedImage(
                component.width,
                component.height,
                java.awt.image.BufferedImage.TYPE_INT_RGB,
            )
        val graphics = image.createGraphics()
        try {
            component.paint(graphics)
        } finally {
            graphics.dispose()
        }
    }

    @Test
    fun lastRowStaysVisibleOnceWrappedTextKnowsItsWidth() {
        lateinit var scroll: JScrollPane
        lateinit var form: JComponent
        val status =
            JLabel(statusDotHtml("AI Completion looks configured.", AuthStatusKind.CONNECTED))
        SwingUtilities.invokeAndWait {
            form = panel {
                repeat(6) { row { comment(help) } }
                row { statusCell(status) }
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
