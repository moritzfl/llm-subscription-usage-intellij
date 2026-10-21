package de.moritzf.quota.idea.settings

import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.panel
import java.awt.Component
import java.awt.Container
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderSettingsPanelLayoutTest {
    private val help =
        "Model for PDF-to-Markdown conversion (subscription_document_to_markdown). Select - to " +
            "turn it off for this account. Local PDFBox text extraction remains available."

    @Test
    fun narrowPaneWrapsTheFormInsteadOfScrollingSideways() = onEdt {
        lateinit var field: JComponent
        val config = panel {
            row("Document model:") {
                field =
                    cell(JBTextField().apply { columns = 40 })
                        .align(AlignX.FILL)
                        .resizableColumn()
                        .comment(help, MAX_LINE_LENGTH_WORD_WRAP)
                        .component
            }
        }
        val panel = providerPanel(config, JPanel())
        val scroll = outerScroll(panel)

        layout(panel, width = 320, height = 2_000)

        assertFalse(scroll.horizontalScrollBar.isVisible, "Narrow form must not scroll sideways")
        assertEquals(scroll.viewport.width, scroll.viewport.view.width)
        val comment = config.descendants().filterIsInstance<JEditorPane>().single()
        assertTrue(right(comment, scroll.viewport) <= scroll.viewport.width, "Help is clipped")
        assertTrue(right(field, scroll.viewport) <= scroll.viewport.width, "Field is clipped")
        assertTrue(comment.height > field.height, "Help must wrap onto several lines")
    }

    @Test
    fun paneBelowTheFormMinimumScrollsSidewaysAtTheMinimum() = onEdt {
        val config = panel { row("Document model:") { cell(JBTextField().apply { columns = 40 }) } }
        val panel = providerPanel(config, JPanel())
        val scroll = outerScroll(panel)

        layout(panel, width = 60, height = 2_000)

        assertTrue(scroll.horizontalScrollBar.isVisible)
        assertEquals(scroll.viewport.view.minimumSize.width, scroll.viewport.view.width)
    }

    @Test
    fun quotaResponseTakesTheSpareHeight() = onEdt {
        val response = JPanel()
        val panel = providerPanel(panel { row("Name:") { cell(JBTextField()) } }, response)
        val scroll = outerScroll(panel)

        layout(panel, width = 600, height = 900)

        assertFalse(scroll.verticalScrollBar.isVisible)
        assertEquals(
            scroll.viewport.height,
            SwingUtilities.convertPoint(response, 0, response.height, scroll.viewport).y,
        )
        assertTrue(response.height > response.preferredSize.height)
    }

    private fun providerPanel(config: JComponent, response: JComponent) =
        object : ProviderSettingsPanel() {
            init {
                install(config, response)
            }

            override fun updateFields() = Unit

            override fun updateStatus() = Unit

            override fun updateResponseArea() = Unit
        }

    private fun outerScroll(panel: Container): JScrollPane =
        panel.descendants().filterIsInstance<JScrollPane>().first()

    private fun layout(panel: Container, width: Int, height: Int) {
        panel.setSize(width, height)
        layoutHeadless(panel)
    }

    private fun right(component: Component, ancestor: Component): Int =
        SwingUtilities.convertPoint(component, component.width, 0, ancestor).x

    private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
}
