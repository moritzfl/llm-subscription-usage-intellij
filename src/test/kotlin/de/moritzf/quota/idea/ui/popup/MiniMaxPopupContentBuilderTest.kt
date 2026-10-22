package de.moritzf.quota.idea.ui.popup

import de.moritzf.quota.minimax.MiniMaxQuota
import de.moritzf.quota.minimax.MiniMaxUsageWindow
import java.awt.Container
import javax.swing.JLabel
import kotlin.test.Test
import kotlin.test.assertTrue

class MiniMaxPopupContentBuilderTest {
    @Test
    fun showsTheVideoLaneBoostAndAnUncappedWeek() {
        val section = MiniMaxPopupSection()
        section.update(
            MiniMaxQuota(
                sessionUsage = MiniMaxUsageWindow(usagePercent = 10.0),
                weeklyUsage = MiniMaxUsageWindow(usagePercent = 30.0),
                weeklyBoost = 1.5,
                videoUsage = MiniMaxUsageWindow(usagePercent = 67.0, periodDurationMs = 86_400_000),
            ),
            null,
            true,
        )
        assertTrue(
            labels(section).any { it.startsWith("30% used (150% of the base allowance)") },
            labels(section).toString(),
        )
        assertTrue("Video 1d limit" in labels(section), labels(section).toString())

        section.update(
            MiniMaxQuota(
                sessionUsage = MiniMaxUsageWindow(usagePercent = 10.0),
                weeklyUnlimited = true,
            ),
            null,
            true,
        )
        assertTrue(
            "Weekly limit" in labels(section) && "Unlimited" in labels(section),
            labels(section).toString(),
        )
        assertTrue(labels(section).none { it.startsWith("Video") })
    }

    private fun labels(root: Container): List<String> =
        root.components
            .filter { it.isVisible }
            .flatMap {
                listOfNotNull((it as? JLabel)?.text) +
                    ((it as? Container)?.let(::labels) ?: emptyList())
            }
}
