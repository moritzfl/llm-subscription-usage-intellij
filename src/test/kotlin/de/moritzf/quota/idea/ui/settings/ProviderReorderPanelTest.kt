package de.moritzf.quota.idea.ui.settings

import de.moritzf.quota.idea.common.QuotaProviderType
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderReorderPanelTest {
    @Test
    fun showsEveryProviderIcon() {
        val panel = ProviderReorderPanel(
            initialOrder = QuotaProviderType.defaultProviderOrder(),
            onOrderChanged = {},
            onProviderSelected = {},
        )

        val providerIds = panel.findProviderIds()

        assertEquals(QuotaProviderType.entries.map { it.id }.toSet(), providerIds)
        assertTrue(QuotaProviderType.SUPERGROK.id in providerIds)
    }

    private fun JPanel.findProviderIds(): Set<String> {
        val result = mutableSetOf<String>()
        fun collect(component: java.awt.Component) {
            val providerId = (component as? JComponent)?.getClientProperty("providerId") as? String
            if (providerId != null) {
                result += providerId
            }
            if (component is java.awt.Container) {
                component.components.forEach(::collect)
            }
        }
        collect(this)
        return result
    }
}
