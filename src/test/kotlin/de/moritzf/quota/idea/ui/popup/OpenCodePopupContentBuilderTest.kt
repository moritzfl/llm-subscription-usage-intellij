package de.moritzf.quota.idea.ui.popup

import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.opencode.OpenCodeUsageWindow
import java.awt.Container
import javax.swing.JLabel
import kotlin.test.Test
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

    private fun labels(root: Container): List<String> =
        visible(root).filterIsInstance<JLabel>().map { it.text }

    private fun visible(root: Container): List<java.awt.Component> =
        root.components
            .filter { it.isVisible }
            .flatMap { listOf(it) + ((it as? Container)?.let(::visible) ?: emptyList()) }
}
