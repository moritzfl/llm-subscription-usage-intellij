package de.moritzf.quota.idea.ui.popup

import de.moritzf.quota.antigravity.AntigravityQuota
import de.moritzf.quota.antigravity.AntigravityUsageWindow
import java.awt.Container
import javax.swing.JLabel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AntigravityPopupContentBuilderTest {
    private val window =
        AntigravityUsageWindow("gemini-weekly", "Gemini Models", "Weekly", "weekly", 0.75)

    @Test
    fun showsRemainingCreditsOnlyWhenReported() {
        val section = AntigravityPopupSection()

        section.update(AntigravityQuota(listOf(window), remainingCredits = 1250), null, true)
        val texts = labels(section)
        assertTrue(
            texts.last().startsWith("Credit balance: 1") && texts.last().endsWith("250"),
            "the credit balance closes the section: $texts",
        )

        section.update(AntigravityQuota(listOf(window)), null, true)
        assertFalse(labels(section).any { it.startsWith("Credit balance") })

        section.update(AntigravityQuota(listOf(window), remainingCredits = 5), "AGY failed", true)
        assertFalse(labels(section).any { it.startsWith("Credit balance") })
    }

    private fun labels(root: Container): List<String> =
        visible(root).filterIsInstance<JLabel>().map { it.text }

    private fun visible(root: Container): List<java.awt.Component> =
        root.components
            .filter { it.isVisible }
            .flatMap { listOf(it) + ((it as? Container)?.let(::visible) ?: emptyList()) }
}
