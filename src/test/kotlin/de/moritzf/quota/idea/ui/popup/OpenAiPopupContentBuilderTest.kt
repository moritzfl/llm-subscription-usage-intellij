package de.moritzf.quota.idea.ui.popup

import com.intellij.ui.components.ActionLink
import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.openai.OpenAiExtraRateLimit
import de.moritzf.quota.openai.OpenAiUsageResponseFixtures
import de.moritzf.quota.openai.RateLimitResetCredit
import de.moritzf.quota.openai.UsageWindow
import java.awt.Container
import java.time.Duration
import javax.swing.JLabel
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class OpenAiPopupContentBuilderTest {
    @Test
    fun resetLinkListsEveryTokenExpirationIncludingMissingDetails() {
        val section = OpenAiPopupSection()
        val now = Clock.System.now()
        val quota =
            OpenAiCodexQuota(
                resetCreditsAvailableCount = 3,
                resetCredits =
                    listOf(
                        RateLimitResetCredit("credit-1", "available", now + 3.days),
                        RateLimitResetCredit("credit-2", "available", now + 10.days),
                    ),
            )

        section.update(quota, error = null, visible = true)

        val tooltip =
            section.components
                .filterIsInstance<JPanel>()
                .flatMap { it.components.filterIsInstance<ActionLink>() }
                .single()
                .toolTipText
        assertTrue(tooltip.contains("Token 1: Expires in "))
        assertTrue(tooltip.contains("Token 2: Expires in "))
        assertTrue(tooltip.contains("Token 3: Expiration unknown"))
        assertFalse(tooltip.contains("credit-"))
    }

    @Test
    fun showsAllExtraRateLimitTitles() {
        val section = OpenAiPopupSection()
        val quota =
            OpenAiCodexQuota(
                resetCreditsAvailableCount = 1,
                extraRateLimits =
                    (1..5).map { index ->
                        OpenAiExtraRateLimit(
                            id = "extra-$index",
                            title = "Extra Model $index Hourly",
                            window =
                                UsageWindow(
                                    usedPercent = index * 10.0,
                                    windowDuration = Duration.ofHours(1),
                                ),
                        )
                    },
            )

        section.update(quota, error = null, visible = true)

        val labels =
            section.components.filterIsInstance<WindowBlockPanel>().flatMap { block ->
                block.components.filterIsInstance<JLabel>().map { it.text }
            }
        assertTrue(labels.contains("Extra Model 5 Hourly"))

        val components = section.components.toList()
        val lastExtraIndex = components.indexOfLast { it.containsLabel("Extra Model 5 Hourly") }
        val resetIndex = components.indexOfFirst { it.containsLabel("Resets available: 1") }
        assertTrue(lastExtraIndex >= 0)
        assertTrue(resetIndex > lastExtraIndex)
    }

    @Test
    fun showsTitleCasedPlanWithoutSelfServePrefix() {
        val section = OpenAiPopupSection()
        val quota =
            OpenAiCodexQuota(
                planType = "self_serve_business_prolite",
                primary = UsageWindow(usedPercent = 0.0, windowDuration = Duration.ofDays(7)),
            )

        section.update(quota, error = null, visible = true)

        assertTrue(section.components.any { it.containsLabel("Codex (Business Prolite)") })
    }

    @Test
    fun showsRemainingSpendWhenHasCreditsIsFalse() {
        val section = OpenAiPopupSection()
        val quota = OpenAiUsageResponseFixtures.businessProliteWithRemainingIndividualSpend()

        section.update(quota, error = null, visible = true)

        assertTrue(section.components.any { it.containsLabel("Assigned credits") })
        assertTrue(section.components.any { it.containsLabel("3.14 / 10 credits") })
        assertTrue(section.components.none { it.containsLabel("Depleted") })
    }

    @Test
    fun showsAccountNameWhenSameTypeHasDuplicates() {
        val section = OpenAiPopupSection()
        section.accountTitle = "Work"
        val quota =
            OpenAiCodexQuota(
                planType = "self_serve_business_prolite",
                primary = UsageWindow(usedPercent = 0.0, windowDuration = Duration.ofDays(7)),
            )

        section.update(quota, error = null, visible = true)

        assertTrue(section.components.any { it.containsLabel("Codex (Work)") })
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
