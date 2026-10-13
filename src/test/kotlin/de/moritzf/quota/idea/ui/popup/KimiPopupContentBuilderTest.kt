package de.moritzf.quota.idea.ui.popup

import de.moritzf.quota.idea.ui.indicator.kimiBarDisplayText
import de.moritzf.quota.kimi.KimiQuota
import de.moritzf.quota.kimi.KimiUsageWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KimiPopupContentBuilderTest {
    @Test
    fun showsMonthlyExhaustionAndClearsModernWindowsForLegacyAccount() {
        val section = KimiPopupSection()
        val quota =
            KimiQuota(
                sessionUsage = KimiUsageWindow(usagePercent = 25.0),
                weeklyUsage = KimiUsageWindow(usagePercent = 40.0),
                monthlyUsage = KimiUsageWindow(usagePercent = 100.0),
            )
        section.update(quota, error = null, visible = true)

        val blocks = section.components.filterIsInstance<WindowBlockPanel>()
        assertEquals(listOf(true, true, true, false), blocks.map { it.isVisible })
        val warning =
            section.components.filterIsInstance<TruncatingPopupLabel>().single {
                it.text == "Kimi limit reached"
            }
        assertTrue(warning.isVisible)
        assertEquals("100%", kimiBarDisplayText(quota, error = null))

        section.update(KimiQuota(totalUsage = KimiUsageWindow(usagePercent = 10.0)), null, true)
        assertEquals(listOf(false, false, false, true), blocks.map { it.isVisible })
        assertFalse(warning.isVisible)
    }
}
