package de.moritzf.quota.idea.ui.popup

import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.opencode.OpenCodeUsageWindow
import java.awt.Container
import javax.swing.JLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class OpenCodePopupContentBuilderTest {
    private val window = OpenCodeUsageWindow(usagePercent = 10.0)

    @Test
    fun titleNamesTheGoPlan() {
        val section = OpenCodePopupSection()

        section.update(OpenCodeQuota(rollingUsage = window, product = "go_plus"), null, true)
        assertTrue("OpenCode Go Plus" in labels(section), labels(section).toString())

        section.update(OpenCodeQuota(rollingUsage = window, product = "go"), null, true)
        assertTrue("OpenCode Go" in labels(section))

        section.update(OpenCodeQuota(rollingUsage = window), null, true)
        assertTrue("OpenCode Go" in labels(section))

        section.update(OpenCodeQuota(availableBalance = 5_000_000L), null, true)
        assertTrue("OpenCode Zen" in labels(section))
    }

    @Test
    fun creditBalanceClosesTheSectionWithTheSignBeforeTheDollar() {
        val section = OpenCodePopupSection()

        section.update(
            OpenCodeQuota(
                rollingUsage = window,
                weeklyUsage = window,
                monthlyUsage = window,
                availableBalance = -567_814L,
            ),
            null,
            true,
        )
        assertEquals("Credit balance: -\$0.01", labels(section).last(), labels(section).toString())

        section.update(OpenCodeQuota(availableBalance = 1_234_567_890L), null, true)
        assertEquals(
            listOf("OpenCode Zen", "Credit balance: \$12.35"),
            labels(section),
        )
    }

    @Test
    fun canceledSubscriptionShowsWhenItEnds() {
        val section = OpenCodePopupSection()

        section.update(
            OpenCodeQuota(rollingUsage = window, endsAt = Instant.parse("2026-11-22T05:19:47Z")),
            null,
            true,
        )
        assertTrue(
            labels(section).any { it.startsWith("Canceled: ends ") },
            labels(section).toString(),
        )

        section.update(OpenCodeQuota(rollingUsage = window), null, true)
        assertFalse(labels(section).any { it.startsWith("Canceled") })
    }

    @Test
    fun showsPausedPaymentAndBalanceBillingStates() {
        val section = OpenCodePopupSection()

        section.update(OpenCodeQuota(product = "go-plus", paused = true), null, true)
        assertTrue("OpenCode Go Plus" in labels(section), labels(section).toString())
        assertTrue(
            "Go Plus is paused. Open your subscription to restore access." in labels(section),
            labels(section).toString(),
        )

        section.update(
            OpenCodeQuota(rollingUsage = window, product = "go", paymentFailed = true),
            null,
            true,
        )
        assertTrue(labels(section).any { it.startsWith("Go renewal payment failed") })

        val limited = OpenCodeUsageWindow("rate-limited", 60, 100.0)
        section.update(
            OpenCodeQuota(rollingUsage = limited, useBalance = true, availableCredit = 1),
            null,
            true,
        )
        assertTrue(labels(section).any { it.startsWith("Go limit reached. Requests are billed") })
        section.update(OpenCodeQuota(rollingUsage = limited), null, true)
        assertTrue("OpenCode limit reached" in labels(section))
    }

    private fun labels(root: Container): List<String> =
        visible(root).filterIsInstance<JLabel>().map { it.text }

    private fun visible(root: Container): List<java.awt.Component> =
        root.components
            .filter { it.isVisible }
            .flatMap { listOf(it) + ((it as? Container)?.let(::visible) ?: emptyList()) }
}
