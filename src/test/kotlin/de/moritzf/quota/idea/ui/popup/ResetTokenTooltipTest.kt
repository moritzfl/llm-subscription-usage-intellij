package de.moritzf.quota.idea.ui.popup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ResetTokenTooltipTest {
    @Test
    fun listsRelativeExpirationsForEveryToken() {
        val now = Clock.System.now()

        val tooltip =
            resetTokenTooltip(
                "Redeem one Codex reset",
                listOf(
                    now + 3.days + 12.hours + 1.minutes,
                    now + 2.hours + 30.minutes + 30.seconds,
                    null,
                ),
            )

        assertEquals(
            "<html>Redeem one Codex reset" +
                "<br>Token 1: Expires in 3d 12h" +
                "<br>Token 2: Expires in 2h 30m" +
                "<br>Token 3: Expiration unknown</html>",
            tooltip,
        )
    }

    @Test
    fun countOnlyResponseDoesNotInventExpirationDates() {
        assertEquals(
            "<html>Redeem one Codex reset" +
                "<br>Token 1: Expiration unknown<br>Token 2: Expiration unknown</html>",
            resetTokenTooltip("Redeem one Codex reset", emptyList(), 2),
        )
    }

    @Test
    fun escapesHtmlIncludingSubMinuteExpiration() {
        val tooltip =
            resetTokenTooltip("Reset <test> & quota", listOf(Clock.System.now() + 30.seconds))

        assertTrue(tooltip.startsWith("<html>Reset &lt;test&gt; &amp; quota"))
        assertTrue(tooltip.contains("Token 1: Expires in &lt;1m"))
    }
}
