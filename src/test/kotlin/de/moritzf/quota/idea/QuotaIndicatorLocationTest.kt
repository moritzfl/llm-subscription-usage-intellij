package de.moritzf.quota.idea

import de.moritzf.quota.idea.ui.indicator.QuotaIndicatorLocation
import kotlin.test.Test
import kotlin.test.assertEquals

class QuotaIndicatorLocationTest {
    @Test
    fun fromStorageValueFallsBackToStatusBar() {
        assertEquals(QuotaIndicatorLocation.STATUS_BAR, QuotaIndicatorLocation.fromStorageValue(null))
        assertEquals(QuotaIndicatorLocation.STATUS_BAR, QuotaIndicatorLocation.fromStorageValue(""))
        assertEquals(QuotaIndicatorLocation.STATUS_BAR, QuotaIndicatorLocation.fromStorageValue("unknown"))
    }

    @Test
    fun fromStorageValueParsesAllLocationsCaseInsensitively() {
        assertEquals(QuotaIndicatorLocation.STATUS_BAR, QuotaIndicatorLocation.fromStorageValue("STATUS_BAR"))
        assertEquals(QuotaIndicatorLocation.MAIN_TOOLBAR, QuotaIndicatorLocation.fromStorageValue("main_toolbar"))
    }
}
