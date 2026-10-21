package de.moritzf.quota.idea.settings

import com.intellij.mock.MockApplication
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBCheckBox
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubscriptionProxyProvidersLayoutTest {
    @Test
    fun providerCheckboxesUseAvailableWidthAndWrapWhenNeeded() = onEdt {
        val panel = SubscriptionProxySettingsPanel()
        val names =
            QuotaSettingsState.SUBSCRIPTION_PROXY_SUPPORTED_PROVIDERS.map { it.displayName }.toSet()
        val boxes =
            descendants(panel).filterIsInstance<JBCheckBox>().filter { it.text in names }.toList()
        assertEquals(names.size, boxes.size)

        fun rowCount(width: Int): Int {
            panel.setSize(width, 2_000)
            repeat(2) { layoutTree(panel) }
            return boxes.map { SwingUtilities.convertPoint(it, 0, 0, panel).y }.distinct().size
        }

        val narrowRows = rowCount(320)
        val wideRows = rowCount(2_000)
        assertTrue(narrowRows > 1, "Narrow provider list must wrap")
        assertTrue(wideRows < narrowRows, "Wider pane must fit provider checkboxes in fewer rows")
    }

    @Test
    fun narrowProxyTabWrapsInsteadOfScrollingSideways() = onEdt {
        val panel = SubscriptionProxySettingsPanel()
        val tab = verticalScrollPane(panel)
        tab.setSize(520, 3_000)
        layoutHeadless(tab)

        assertFalse(tab.horizontalScrollBar.isVisible, "Proxy tab must not scroll sideways")
        val viewport = tab.viewport
        val clipped =
            panel
                .descendants()
                .filter { it.width > 0 && (it as java.awt.Container).componentCount == 0 }
                .filter {
                    generateSequence(it) { c -> c.parent }
                        .takeWhile { c -> c !== panel }
                        .all { c -> c.isVisible }
                }
                .filter {
                    SwingUtilities.convertPoint(it, it.width, 0, viewport).x > viewport.width
                }
                .map { "${it.javaClass.simpleName}(${(it as? javax.swing.JLabel)?.text})" }
                .toList()
        assertTrue(clipped.isEmpty(), "Clipped at the right edge: $clipped")
    }

    private fun onEdt(block: () -> Unit) {
        SwingUtilities.invokeAndWait {
            val disposable = Disposer.newDisposable()
            val app = MockApplication.setUp(disposable)
            val job = kotlinx.coroutines.SupervisorJob()
            app.registerService(
                kotlinx.coroutines.CoroutineScope::class.java,
                kotlinx.coroutines.CoroutineScope(job + kotlinx.coroutines.Dispatchers.Default),
            )
            app.registerService(
                com.intellij.ui.ExperimentalUI::class.java,
                object : com.intellij.ui.ExperimentalUI() {
                    override fun saveCurrentValueAndReapplyDefaultLaf() = Unit

                    override fun earlyInitValue() = true
                },
            )
            try {
                block()
            } finally {
                job.cancel()
                Disposer.dispose(disposable)
            }
        }
    }

    private fun layoutTree(container: java.awt.Container) {
        container.invalidate()
        container.doLayout()
        container.components.filterIsInstance<java.awt.Container>().forEach(::layoutTree)
    }

    private fun descendants(root: java.awt.Container): Sequence<java.awt.Component> =
        root.components.asSequence().flatMap {
            sequenceOf(it) + if (it is java.awt.Container) descendants(it) else emptySequence()
        }
}
