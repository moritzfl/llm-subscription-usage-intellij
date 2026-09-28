package de.moritzf.quota.idea.settings

import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JViewport
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderSettingsPanelLayoutTest {
    @Test
    fun horizontalScrollbarLeavesBottomHelpFullyVisible() {
        SwingUtilities.invokeAndWait {
            val help = JLabel("Used by subscription_vision. '-' keeps vision off.")
            val config = JPanel(BorderLayout()).apply {
                preferredSize = Dimension(900, 200)
                add(help, BorderLayout.SOUTH)
            }
            object : ProviderSettingsPanel() {
                init { install(config, JPanel()) }
                override fun updateFields() = Unit
                override fun updateStatus() = Unit
                override fun updateResponseArea() = Unit
            }
            val viewport = config.parent as JViewport
            val scroll = viewport.parent as JScrollPane

            for (width in listOf(320, 1100, 320)) {
                scroll.setSize(width, scroll.preferredSize.height)
                scroll.doLayout()
                viewport.doLayout()
                config.doLayout()

                assertTrue(viewport.height >= config.preferredSize.height, "Content clipped at width $width")
                assertTrue(help.y + help.height <= viewport.height, "Help clipped at width $width")
                assertFalse(scroll.verticalScrollBar.isVisible)
                if (width < config.preferredSize.width) {
                    assertTrue(scroll.horizontalScrollBar.isVisible)
                    val helpBottom = viewport.y + config.y + help.y + help.height
                    assertTrue(helpBottom <= scroll.horizontalScrollBar.y, "Scrollbar covers help")
                } else {
                    assertFalse(scroll.horizontalScrollBar.isVisible)
                }
            }
        }
    }
}
