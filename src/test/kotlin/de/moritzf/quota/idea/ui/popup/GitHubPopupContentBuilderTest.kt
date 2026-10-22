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
        val quota =
            GitHubQuota(
                plan = "Copilot Individual",
                premiumInteractions =
                    GitHubUsageWindow(label = "Premium requests", usagePercent = 26.833),
                chat = GitHubUsageWindow(label = "Chat", unlimited = true),
                completions = GitHubUsageWindow(label = "Completions", unlimited = true),
            )

        section.update(quota, error = null, visible = true)

        val blocks = section.components.filterIsInstance<WindowBlockPanel>()
        assertEquals(4, blocks.size)
        assertTrue(blocks[0].isVisible)
        assertFalse(blocks[1].isVisible)
        assertFalse(blocks[2].isVisible)
    }

    @Test
    fun showsTheAdditionalBudgetOnceIncludedUsageIsUsedUp() {
        val section = GitHubPopupSection()
        section.update(
            GitHubQuota(
                plan = "Copilot Pro",
                premiumInteractions =
                    GitHubUsageWindow(
                        label = "AI credits",
                        usagePercent = 100.0,
                        overagePermitted = true,
                        overageUsed = 250.5,
                        overageLimit = 1000.0,
                    ),
            ),
            error = null,
            visible = true,
        )

        val texts =
            section.components.flatMap { component ->
                listOfNotNull((component as? javax.swing.JLabel)?.takeIf { it.isVisible }?.text) +
                    ((component as? WindowBlockPanel)
                            ?.takeIf { it.isVisible }
                            ?.components
                            .orEmpty())
                        .mapNotNull { (it as? javax.swing.JLabel)?.text }
            }
        assertTrue(
            "Included usage is used up; extra usage is in use" in texts,
            texts.toString(),
        )
        assertTrue("Extra usage" in texts, texts.toString())
        assertTrue("250.5 of 1000 used" in texts, texts.toString())
        assertFalse("GitHub Copilot limit reached" in texts)
    }

    @Test
    fun managedPlanStopsAtTheIncludedAmountEvenWithABudget() {
        val section = GitHubPopupSection()
        section.update(
            GitHubQuota(
                plan = "Copilot Business",
                premiumInteractions =
                    GitHubUsageWindow(
                        label = "AI credits",
                        usagePercent = 100.0,
                        overagePermitted = true,
                        overageUsed = 0.0,
                        overageLimit = 1000.0,
                    ),
            ),
            error = null,
            visible = true,
        )

        val texts =
            section.components.flatMap { component ->
                listOfNotNull((component as? javax.swing.JLabel)?.takeIf { it.isVisible }?.text) +
                    ((component as? WindowBlockPanel)
                            ?.takeIf { it.isVisible }
                            ?.components
                            .orEmpty())
                        .mapNotNull { (it as? javax.swing.JLabel)?.text }
            }
        assertTrue("GitHub Copilot limit reached" in texts, texts.toString())
        assertFalse("Included usage is used up; extra usage is in use" in texts)
    }

    @Test
    fun showsEndedSubscriptionMessage() {
        val section = GitHubPopupSection()
        val quota =
            GitHubQuota(
                plan = "Copilot Individual",
                subscriptionState = GitHubSubscriptionState.SUBSCRIPTION_ENDED,
            )

        section.update(quota, error = null, visible = true)

        val message =
            section.components.filterIsInstance<TruncatingPopupLabel>().single {
                it.text == "GitHub Copilot subscription ended"
            }
        assertTrue(message.isVisible)
        assertEquals("GitHub Copilot subscription ended", message.text)
        section.components.filterIsInstance<WindowBlockPanel>().forEach { block ->
            assertFalse(block.isVisible)
        }
    }
}
