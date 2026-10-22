package de.moritzf.quota.idea.ui.popup

import com.intellij.ui.components.ActionLink
import de.moritzf.quota.claude.ClaudeCredit
import de.moritzf.quota.claude.ClaudeExtraUsage
import de.moritzf.quota.claude.ClaudeMoney
import de.moritzf.quota.claude.ClaudeQuota
import de.moritzf.quota.claude.ClaudeResetGrant
import de.moritzf.quota.claude.ClaudeResets
import de.moritzf.quota.claude.ClaudeSessionReset
import de.moritzf.quota.claude.ClaudeUsageWindow
import java.awt.Container
import javax.swing.JLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class ClaudePopupContentBuilderTest {
    private val window = ClaudeUsageWindow(label = "5-hour", usagePercent = 40.0)

    @Test
    fun resetLinkCountsEveryResetAndDescribesThem() {
        val section = ClaudePopupSection()
        val resets =
            ClaudeResets(
                grants =
                    listOf(
                        grant("weekly_gift", resetsLeft = 2, clears = listOf("seven_day")),
                        grant("paused_gift", paused = true),
                    ),
                sessionReset = ClaudeSessionReset(null),
            )

        section.update(ClaudeQuota(fiveHourUsage = window, resets = resets), null, true)

        assertTrue(labels(section).contains("Resets available: 3"))
        val tooltip = links(section).single().toolTipText
        assertTrue(tooltip.contains("Session reset: refills your 5-hour limit."))
        assertTrue(tooltip.contains("Weekly reset: refills your weekly limit. 2 left. Expires in "))
        assertFalse(tooltip.contains("weekly_gift"))
    }

    @Test
    fun resetsUsableOnlyAtALimitAreListedWithoutALink() {
        val section = ClaudePopupSection()
        val resets = ClaudeResets(grants = listOf(grant("session_gift", usableNow = false)))

        section.update(ClaudeQuota(fiveHourUsage = window, resets = resets), null, true)

        assertTrue(links(section).isEmpty())
        assertTrue(labels(section).contains("Resets available: 1"))
        assertTrue(labels(section).contains("(usable at a usage limit)"))
    }

    @Test
    fun hidesResetsWhenNoneAreOffered() {
        val section = ClaudePopupSection()
        section.update(
            ClaudeQuota(
                fiveHourUsage = window,
                resets = ClaudeResets(sessionReset = ClaudeSessionReset(null)),
            ),
            null,
            true,
        )

        section.update(ClaudeQuota(fiveHourUsage = window), null, true)

        assertEquals(emptyList(), links(section))
        assertFalse(labels(section).any { it.startsWith("Resets available") })
    }

    @Test
    fun showsPlanCreditsAndTheUsageCreditBalance() {
        val section = ClaudePopupSection()

        section.update(
            ClaudeQuota(
                plan = "Max 20x",
                fiveHourUsage = window,
                credits =
                    listOf(
                        ClaudeCredit(
                            "Claude Code and Cowork credit",
                            25.0,
                            endsAt = Clock.System.now() + 3.days,
                        ),
                        ClaudeCredit(
                            "Fable credit",
                            40.0,
                            20.0,
                            50.0,
                            Clock.System.now() + 3.days,
                            renews = true,
                        ),
                    ),
                creditBalance = ClaudeMoney(1234, "EUR"),
            ),
            null,
            true,
        )

        val texts = labels(section)
        assertTrue("Claude (Max 20x)" in texts, texts.toString())
        assertTrue("Claude Code and Cowork credit" in texts)
        assertTrue(texts.any { it.startsWith("25% used - Expires in ") }, texts.toString())
        assertTrue("Fable credit" in texts)
        assertTrue(
            texts.any { it.startsWith("\$20.00 of \$50.00 used - Resets in ") },
            texts.toString(),
        )
        assertEquals("Credit balance: €12.34", texts.last(), texts.toString())
        assertFalse(texts.contains("Claude limit reached"))
    }

    @Test
    fun showsUnlimitedAndBlockedUsageCredits() {
        val section = ClaudePopupSection()

        section.update(
            ClaudeQuota(
                fiveHourUsage = window,
                extraUsage =
                    ClaudeExtraUsage(
                        isEnabled = true,
                        usedCredits = 1250,
                        currency = "USD",
                        unlimited = true,
                    ),
            ),
            null,
            true,
        )
        assertTrue(
            "\$12.50 used • No monthly cap" in labels(section),
            labels(section).toString(),
        )

        section.update(
            ClaudeQuota(
                fiveHourUsage = window,
                extraUsage = ClaudeExtraUsage(disabledReason = "out_of_credits"),
            ),
            null,
            true,
        )
        assertTrue(
            "Extra usage off: out of usage credits" in labels(section),
            labels(section).toString(),
        )
        assertFalse("Extra usage" in labels(section), "blocked extra usage has no heading")
        assertFalse(labels(section).any { it.startsWith("Usage credit balance") })

        section.update(
            ClaudeQuota(
                fiveHourUsage = window,
                extraUsage = ClaudeExtraUsage(disabledReason = "overage_not_provisioned"),
            ),
            null,
            true,
        )
        assertFalse("Extra usage" in labels(section), "not set up is not a blocked state")
    }

    private fun grant(
        id: String,
        resetsLeft: Int = 1,
        paused: Boolean = false,
        usableNow: Boolean = true,
        clears: List<String> = listOf("five_hour"),
    ) =
        ClaudeResetGrant(
            id = id,
            label = "Weekly reset",
            resetsLeft = resetsLeft,
            endsAt = Clock.System.now() + 3.days,
            paused = paused,
            usableNow = usableNow,
            clears = clears,
        )

    private fun links(root: Container): List<ActionLink> =
        visible(root).filterIsInstance<ActionLink>()

    private fun labels(root: Container): List<String> =
        visible(root).filter { it is JLabel && it !is ActionLink }.map { (it as JLabel).text }

    private fun visible(root: Container): List<java.awt.Component> =
        root.components
            .filter { it.isVisible }
            .flatMap { listOf(it) + ((it as? Container)?.let(::visible) ?: emptyList()) }
}
