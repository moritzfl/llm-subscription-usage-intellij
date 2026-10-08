package de.moritzf.quota.idea.ui.settings

import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.settings.ProviderSettingsRegistry
import de.moritzf.quota.idea.ui.indicator.ProviderUiRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderReorderPanelTest {
    @Test
    fun showsEveryProvider() {
        val panel =
            ProviderReorderPanel(
                initialOrder = QuotaProviderType.defaultProviderOrder(),
                onOrderChanged = {},
                onProviderSelected = {},
            )

        assertEquals(
            ProviderUiRegistry.all.keys.map { it.id }.toSet(),
            panel.getOrder().map { it.id }.toSet(),
        )
        assertTrue(QuotaProviderType.SUPERGROK in panel.getOrder())
    }

    @Test
    fun uiAndSettingsRegistriesCoverEveryProviderType() {
        assertEquals(QuotaProviderType.entries.toSet(), ProviderUiRegistry.all.keys)
        assertEquals(QuotaProviderType.entries.toSet(), ProviderSettingsRegistry.all.keys)
    }

    @Test
    fun selectsFirstProviderFromCustomOrder() {
        val order = QuotaProviderType.defaultProviderOrder().withFirst(QuotaProviderType.SUPERGROK)
        val panel =
            ProviderReorderPanel(
                initialOrder = order,
                onOrderChanged = {},
                onProviderSelected = {},
            )

        assertEquals(QuotaProviderType.SUPERGROK, panel.getSelectedProvider())
    }

    @Test
    fun resetOrderSelectsFirstProviderFromNewOrder() {
        var selected: QuotaProviderType? = null
        val panel =
            ProviderReorderPanel(
                initialOrder = QuotaProviderType.defaultProviderOrder(),
                onOrderChanged = {},
                onProviderSelected = { selected = it },
            )

        panel.setOrder(QuotaProviderType.defaultProviderOrder().withFirst(QuotaProviderType.KIMI))

        assertEquals(QuotaProviderType.KIMI, panel.getSelectedProvider())
        assertEquals(QuotaProviderType.KIMI, selected)
    }

    @Test
    fun filterNarrowsVisibleProviders() {
        val panel =
            ProviderReorderPanel(
                initialOrder = QuotaProviderType.defaultProviderOrder(),
                onOrderChanged = {},
                onProviderSelected = {},
            )

        panel.setFilterText("grok")

        assertEquals(listOf(QuotaProviderType.SUPERGROK), panel.visibleProviders())
        assertEquals(QuotaProviderType.defaultProviderOrder(), panel.getOrder())
    }

    @Test
    fun filterHidesSelectionFromDetailCallback() {
        var selected: QuotaProviderType? = QuotaProviderType.CLAUDE
        val panel =
            ProviderReorderPanel(
                initialOrder = QuotaProviderType.defaultProviderOrder(),
                onOrderChanged = {},
                onProviderSelected = { selected = it },
            )

        panel.setFilterText("kimi")

        assertEquals(null, selected)
        assertEquals(QuotaProviderType.defaultProviderOrder().first(), panel.getSelectedProvider())
    }

    @Test
    fun moveSelectedReordersAndKeepsSelection() {
        val changes = mutableListOf<List<QuotaProviderType>>()
        val panel =
            ProviderReorderPanel(
                initialOrder = QuotaProviderType.defaultProviderOrder(),
                onOrderChanged = { changes += it },
                onProviderSelected = {},
            )
        val first = panel.getSelectedProvider()

        panel.moveSelected(1)

        assertEquals(first, panel.getOrder()[1])
        assertEquals(first, panel.getSelectedProvider())
        assertEquals(first, changes.single()[1])
    }

    @Test
    fun moveSelectedIsNoOpWhileFiltered() {
        val changes = mutableListOf<List<QuotaProviderType>>()
        val original = QuotaProviderType.defaultProviderOrder()
        val panel =
            ProviderReorderPanel(
                initialOrder = original,
                onOrderChanged = { changes += it },
                onProviderSelected = {},
            )

        panel.setFilterText("open")
        panel.moveSelected(1)

        assertEquals(original, panel.getOrder())
        assertTrue(changes.isEmpty())
    }

    private fun List<QuotaProviderType>.withFirst(
        type: QuotaProviderType
    ): List<QuotaProviderType> {
        return listOf(type) + filterNot { it == type }
    }
}
