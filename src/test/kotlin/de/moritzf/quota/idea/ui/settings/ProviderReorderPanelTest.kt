package de.moritzf.quota.idea.ui.settings

import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.settings.ProviderSettingsRegistry
import de.moritzf.quota.idea.ui.indicator.ProviderUiRegistry
import java.awt.event.MouseEvent
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

        assertEquals(ProviderUiRegistry.all.keys.map { it.id }.toSet(), providerIds)
        assertTrue(QuotaProviderType.SUPERGROK.id in providerIds)
    }

    @Test
    fun uiAndSettingsRegistriesCoverEveryProviderType() {
        assertEquals(QuotaProviderType.entries.toSet(), ProviderUiRegistry.all.keys)
        assertEquals(QuotaProviderType.entries.toSet(), ProviderSettingsRegistry.all.keys)
    }

    @Test
    fun selectsFirstProviderFromCustomOrder() {
        val order = QuotaProviderType.defaultProviderOrder().withFirst(QuotaProviderType.SUPERGROK)
        val panel = ProviderReorderPanel(
            initialOrder = order,
            onOrderChanged = {},
            onProviderSelected = {},
        )

        assertEquals(QuotaProviderType.SUPERGROK, panel.getSelectedProvider())
    }

    @Test
    fun resetOrderSelectsFirstProviderFromNewOrder() {
        var selected: QuotaProviderType? = null
        val panel = ProviderReorderPanel(
            initialOrder = QuotaProviderType.defaultProviderOrder(),
            onOrderChanged = {},
            onProviderSelected = { selected = it },
        )

        panel.setOrder(QuotaProviderType.defaultProviderOrder().withFirst(QuotaProviderType.KIMI))

        assertEquals(QuotaProviderType.KIMI, panel.getSelectedProvider())
        assertEquals(QuotaProviderType.KIMI, selected)
    }

    @Test
    fun selectedProviderBackgroundIsOpaque() {
        val panel = ProviderReorderPanel(
            initialOrder = QuotaProviderType.defaultProviderOrder(),
            onOrderChanged = {},
            onProviderSelected = {},
        )

        val selectedComponent = panel.findProviderComponents()
            .first { it.getClientProperty("providerId") == panel.getSelectedProvider().id }

        assertTrue(selectedComponent.isOpaque)
        assertEquals(255, selectedComponent.background.alpha)
    }

    @Test
    fun hoveredProviderBackgroundIsOpaque() {
        val panel = ProviderReorderPanel(
            initialOrder = QuotaProviderType.defaultProviderOrder(),
            onOrderChanged = {},
            onProviderSelected = {},
        )
        val hoveredComponent = panel.findProviderComponents()
            .first { it.getClientProperty("providerId") != panel.getSelectedProvider().id }

        val event = MouseEvent(
            hoveredComponent,
            MouseEvent.MOUSE_ENTERED,
            System.currentTimeMillis(),
            0,
            1,
            1,
            0,
            false,
        )
        hoveredComponent.mouseListeners.forEach { it.mouseEntered(event) }

        assertTrue(hoveredComponent.isOpaque)
        assertEquals(255, hoveredComponent.background.alpha)
    }

    private fun List<QuotaProviderType>.withFirst(type: QuotaProviderType): List<QuotaProviderType> {
        return listOf(type) + filterNot { it == type }
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

    private fun JPanel.findProviderComponents(): List<JComponent> {
        val result = mutableListOf<JComponent>()
        fun collect(component: java.awt.Component) {
            if ((component as? JComponent)?.getClientProperty("providerId") != null) {
                result += component
            }
            if (component is java.awt.Container) {
                component.components.forEach(::collect)
            }
        }
        collect(this)
        return result
    }
}
