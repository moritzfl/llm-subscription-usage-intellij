package de.moritzf.quota.idea.ui.popup

import de.moritzf.quota.idea.ui.indicator.kimiBarDisplayText
import de.moritzf.quota.kimi.KimiExtraUsage
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
        assertEquals(listOf(true, true, true, false, false), blocks.map { it.isVisible })
        val warning =
            section.components.filterIsInstance<TruncatingPopupLabel>().single {
                it.text == "Kimi limit reached"
            }
        assertTrue(warning.isVisible)
        assertEquals("100%", kimiBarDisplayText(quota, error = null))

        section.update(KimiQuota(totalUsage = KimiUsageWindow(usagePercent = 10.0)), null, true)
        assertEquals(listOf(false, false, false, true, false), blocks.map { it.isVisible })
        assertFalse(warning.isVisible)
    }

    @Test
    fun showsTheCodeShareAndExtraUsage() {
        val section = KimiPopupSection()
        val amount = { cents: Double -> String.format("%.2f", cents) }

        section.update(
            KimiQuota(
                monthlyUsage = KimiUsageWindow(usagePercent = 40.0),
                monthlyCodeUsage = KimiUsageWindow(usagePercent = 25.0),
                extraUsage =
                    KimiExtraUsage(
                        balanceCents = 10_000,
                        usedCents = 5_000,
                        limitCents = 20_000,
                        limitEnabled = true,
                    ),
            ),
            null,
            true,
        )
        assertTrue("40% used (code 25%)" in labels(section), labels(section).toString())
        assertTrue("\$${amount(50.0)} of \$${amount(200.0)} used this month" in labels(section))
        assertTrue("Booster balance: \$${amount(100.0)}" in labels(section))

        section.update(
            KimiQuota(
                monthlyUsage = KimiUsageWindow(usagePercent = 40.0),
                extraUsage = KimiExtraUsage(usedCents = 1234, currency = "CNY"),
            ),
            null,
            true,
        )
        assertTrue("¥${amount(12.34)} used this month • No monthly cap" in labels(section))
        assertTrue("40% used" in labels(section))
    }

    private fun labels(root: java.awt.Container): List<String> =
        root.components
            .filter { it.isVisible }
            .flatMap {
                listOfNotNull((it as? javax.swing.JLabel)?.text) +
                    ((it as? java.awt.Container)?.let(::labels) ?: emptyList())
            }
}
