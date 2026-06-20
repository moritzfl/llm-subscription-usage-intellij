package de.moritzf.quota.idea.ui.popup

import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.openai.OpenAiExtraRateLimit
import de.moritzf.quota.openai.UsageWindow
import java.awt.Container
import java.time.Duration
import javax.swing.JLabel
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertTrue

class OpenAiPopupContentBuilderTest {
    @Test
    fun showsAllExtraRateLimitTitles() {
        val section = OpenAiPopupSection()
        val quota = OpenAiCodexQuota(
            resetCreditsAvailableCount = 1,
            extraRateLimits = (1..5).map { index ->
                OpenAiExtraRateLimit(
                    id = "extra-$index",
                    title = "Extra Model $index Hourly",
                    window = UsageWindow(usedPercent = index * 10.0, windowDuration = Duration.ofHours(1)),
                )
            },
        )

        section.update(quota, error = null, visible = true)

        val labels = section.components
            .filterIsInstance<WindowBlockPanel>()
            .flatMap { block -> block.components.filterIsInstance<JLabel>().map { it.text } }
        assertTrue(labels.contains("Extra Model 5 Hourly"))

        val components = section.components.toList()
        val lastExtraIndex = components.indexOfLast { it.containsLabel("Extra Model 5 Hourly") }
        val resetIndex = components.indexOfFirst { it.containsLabel("Resets available: 1") }
        assertTrue(lastExtraIndex >= 0)
        assertTrue(resetIndex > lastExtraIndex)
    }

    private fun Any?.containsLabel(text: String): Boolean {
        return when (this) {
            is JLabel -> this.text == text
            is JPanel -> components.any { it.containsLabel(text) }
            is Container -> components.any { it.containsLabel(text) }
            else -> false
        }
    }
}
