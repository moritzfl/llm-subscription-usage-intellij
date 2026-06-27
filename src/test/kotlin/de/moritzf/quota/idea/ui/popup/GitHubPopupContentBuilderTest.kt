package de.moritzf.quota.idea.ui.popup

import de.moritzf.quota.github.GitHubQuota
import de.moritzf.quota.github.GitHubSubscriptionState
import de.moritzf.quota.github.GitHubUsageWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitHubPopupContentBuilderTest {
    @Test
    fun hidesUnlimitedWindows() {
        val section = GitHubPopupSection()
        val quota = GitHubQuota(
            plan = "Copilot Individual",
            premiumInteractions = GitHubUsageWindow(label = "Premium requests", usagePercent = 26.833),
            chat = GitHubUsageWindow(label = "Chat", unlimited = true),
            completions = GitHubUsageWindow(label = "Completions", unlimited = true),
        )

        section.update(quota, error = null, visible = true)

        val blocks = section.components.filterIsInstance<WindowBlockPanel>()
        assertEquals(3, blocks.size)
        assertTrue(blocks[0].isVisible)
        assertFalse(blocks[1].isVisible)
        assertFalse(blocks[2].isVisible)
    }

    @Test
    fun showsEndedSubscriptionMessage() {
        val section = GitHubPopupSection()
        val quota = GitHubQuota(
            plan = "Copilot Individual",
            subscriptionState = GitHubSubscriptionState.SUBSCRIPTION_ENDED,
        )

        section.update(quota, error = null, visible = true)

        val message = section.components.filterIsInstance<TruncatingPopupLabel>().single()
        assertTrue(message.isVisible)
        assertEquals("GitHub Copilot subscription ended", message.text)
        section.components.filterIsInstance<WindowBlockPanel>().forEach { block ->
            assertFalse(block.isVisible)
        }
    }
}
