package de.moritzf.quota.idea

import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class QuotaUiUtilTest {
    @Test
    fun formatResetCompactReturnsNullForNullReset() {
        assertNull(QuotaUiUtil.formatResetCompact(null))
    }

    @Test
    fun formatResetCompactReturnsRelativeValueForFutureReset() {
        val formatted = QuotaUiUtil.formatResetCompact(Clock.System.now().plus(120.seconds))

        assertNotNull(formatted)
        assertFalse(formatted.startsWith("in "))
    }

    @Test
    fun formatResetCompactReturnsNullForPastReset() {
        assertNull(QuotaUiUtil.formatResetCompact(Clock.System.now().minus(60.seconds)))
    }

    @Test
    fun formatOpenCodeBalanceConvertsFractionalUnitsToDollars() {
        assertEquals("12.35", QuotaUiUtil.formatOpenCodeBalance(1_234_567_890L))
        assertEquals("0.00", QuotaUiUtil.formatOpenCodeBalance(0L))
        assertEquals("0.01", QuotaUiUtil.formatOpenCodeBalance(1_000_000L))
        assertEquals("100.00", QuotaUiUtil.formatOpenCodeBalance(10_000_000_000L))
    }
}
