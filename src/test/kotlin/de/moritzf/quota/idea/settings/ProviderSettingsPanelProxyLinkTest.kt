package de.moritzf.quota.idea.settings

import com.intellij.mock.MockApplication
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.ActionLink
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderSettingsPanelProxyLinkTest {
    @Test
    fun proxyLinkSitsAboveQuotaResponseInsteadOfAtTheBottom() = onEdt {
        val response = JLabel("Last quota response")
        val panel =
            object : ProviderSettingsPanel() {
                init {
                    install(JLabel("Connection"), response)
                }

                override fun updateFields() = Unit

                override fun updateStatus() = Unit

                override fun updateResponseArea() = Unit
            }
        panel.showProxySettingsLink {}
        panel.setSize(600, 800)
        layoutTree(panel)

        val link = descendants(panel).filterIsInstance<ActionLink>().single()
        assertEquals("Open proxy settings", link.text)
        assertTrue(
            linkLocation(link, panel).y < linkLocation(response, panel).y,
            "Proxy link must render above the quota response",
        )
    }

    private fun onEdt(block: () -> Unit) {
        SwingUtilities.invokeAndWait {
            val disposable = Disposer.newDisposable()
            MockApplication.setUp(disposable)
            try {
                block()
            } finally {
                Disposer.dispose(disposable)
            }
        }
    }

    private fun layoutTree(container: java.awt.Container) {
        container.doLayout()
        container.components.filterIsInstance<java.awt.Container>().forEach(::layoutTree)
    }

    private fun linkLocation(component: JComponent, panel: JComponent) =
        SwingUtilities.convertPoint(component, 0, 0, panel)

    private fun descendants(root: java.awt.Container): Sequence<java.awt.Component> =
        root.components.asSequence().flatMap {
            sequenceOf(it) + if (it is java.awt.Container) descendants(it) else emptySequence()
        }
}
