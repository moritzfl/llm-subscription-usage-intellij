package de.moritzf.quota.idea

import de.moritzf.quota.idea.settings.QuotaDisplayMode
import de.moritzf.quota.idea.ui.indicator.QuotaIndicatorLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuotaDisplayModeTest {
    @Test
    fun fromStorageValueFallsBackToIconOnly() {
        assertEquals(QuotaDisplayMode.ICON_ONLY, QuotaDisplayMode.fromStorageValue(null))
        assertEquals(QuotaDisplayMode.ICON_ONLY, QuotaDisplayMode.fromStorageValue(""))
        assertEquals(QuotaDisplayMode.ICON_ONLY, QuotaDisplayMode.fromStorageValue("unknown"))
    }

    @Test
    fun fromStorageValueParsesAllModesCaseInsensitively() {
        assertEquals(QuotaDisplayMode.ICON_ONLY, QuotaDisplayMode.fromStorageValue("ICON_ONLY"))
        assertEquals(QuotaDisplayMode.PERCENTAGE_BAR, QuotaDisplayMode.fromStorageValue("percentage_bar"))
        assertEquals(QuotaDisplayMode.CAKE_DIAGRAM, QuotaDisplayMode.fromStorageValue("cake_diagram"))
    }

    @Test
    fun mainToolbarOnlySupportsIconAndCakeModes() {
        val supported = QuotaDisplayMode.supportedFor(QuotaIndicatorLocation.MAIN_TOOLBAR)
        assertTrue(QuotaDisplayMode.ICON_ONLY in supported)
        assertTrue(QuotaDisplayMode.CAKE_DIAGRAM in supported)
        assertFalse(QuotaDisplayMode.PERCENTAGE_BAR in supported)
    }

    @Test
    fun sanitizeForMainToolbarFallsBackFromPercentageBar() {
        assertEquals(
            QuotaDisplayMode.ICON_ONLY,
            QuotaDisplayMode.sanitizeFor(QuotaIndicatorLocation.MAIN_TOOLBAR, QuotaDisplayMode.PERCENTAGE_BAR),
        )
    }
}
