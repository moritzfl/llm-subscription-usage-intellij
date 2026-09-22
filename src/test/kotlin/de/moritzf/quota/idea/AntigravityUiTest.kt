package de.moritzf.quota.idea

import de.moritzf.quota.antigravity.USAGE_REPORT
import de.moritzf.quota.antigravity.parseAntigravityQuota
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.ui.indicator.AntigravityUi
import de.moritzf.quota.idea.ui.indicator.ProviderAuthState
import de.moritzf.quota.idea.ui.indicator.buildIndicatorTooltip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AntigravityUiTest {
    @Test
    fun showsUsedRatherThanRemainingPercentageForMostConstrainedGroup() {
        val quota = parseAntigravityQuota(USAGE_REPORT)
        assertEquals(75, AntigravityUi.displayPercent(quota, null))
        assertTrue(AntigravityUi.barText(quota, null).startsWith("75%"))
        val tooltip = buildIndicatorTooltip(QuotaProviderType.ANTIGRAVITY, quota, null, ProviderAuthState.AUTHENTICATED)
        assertTrue(tooltip.contains("75% used (Claude and GPT models / weekly)"), tooltip)
    }

    @Test
    fun unknownUsageDoesNotRenderEmptyQuotaBarOrInventPeriod() {
        val quota = parseAntigravityQuota("""
            {"status":"SUCCESS","command":{"name":"usage","data":{"groups":[
                {"name":"Models","buckets":[{"id":"unknown","reset_time":"2026-09-29T00:00:00Z"}]}
            ]}}}
        """.trimIndent())
        assertEquals(-1, AntigravityUi.displayPercent(quota, null))
        assertEquals("no data", AntigravityUi.barText(quota, null))
        assertNull(AntigravityUi.periodElapsedFraction(quota, null))
    }
}
