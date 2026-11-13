package de.moritzf.quota.idea.ui.popup

import com.intellij.ui.components.ActionLink
import de.moritzf.quota.claude.ClaudeCredit
import de.moritzf.quota.claude.ClaudeExtraUsage
import de.moritzf.quota.claude.ClaudeMoney
import de.moritzf.quota.claude.ClaudeQuota
import de.moritzf.quota.claude.ClaudeResetChoice
import de.moritzf.quota.claude.ClaudeResetGrant
import de.moritzf.quota.claude.ClaudeResets
import de.moritzf.quota.claude.ClaudeSessionReset
import de.moritzf.quota.claude.ClaudeUsageWindow
import java.awt.Container
import javax.swing.JLabel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

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

        assertTrue(labels(section).contains("Resets: 3"))
        val tooltip = links(section).single().toolTipText
        assertTrue(tooltip.contains("Session reset: 5-hour limit only"))
        assertTrue(tooltip.contains("Reset 1: weekly limit · 2 left · Expires in "))
        assertFalse(tooltip.contains("weekly_gift"))
    }

    @Test
    fun mixedResetSummaryDistinguishesUsableAndRestrictedCounts() {
        val section = ClaudePopupSection()
        val resets =
            ClaudeResets(
                grants = listOf(grant("usable", 2), grant("restricted", 3, usableNow = false)),
            )
        section.update(ClaudeQuota(fiveHourUsage = window, resets = resets), null, true)
        assertTrue("Resets: 5 · 2 ready" in labels(section))
        assertTrue(links(section).single().toolTipText.contains("3 left · Expires in "))
        assertTrue(links(section).single().toolTipText.contains("At limit only"))
    }

    @Test
    fun resetChooserRequiresSelectionAndKeepsLongNamesAndGrantIdentity() {
        val recommended =
            grant("recommended", 2).copy(label = "A long <launch> grant for eligible accounts")
        val second = grant("second").copy(label = recommended.label)
        val restricted = grant("restricted", usableNow = false)
        val resets =
            ClaudeResets(listOf(second, restricted, recommended), nextGrantId = recommended.id)
        var changes = 0
        val panel = ClaudeResetSelectionPanel(resets) { changes++ }
        val options = visible(panel).filterIsInstance<JRadioButton>()
        assertEquals(3, options.size)
        assertNull(panel.selectedChoice)
        assertTrue(options[0].text.contains("A long &lt;launch&gt; grant"))
        assertTrue(options[0].text.contains("Reset 1:"))
        assertTrue(options[1].text.contains("Reset 2:"))
        assertTrue(options[2].text.contains("Reset 3:"))
        assertTrue(options[0].text.contains("Suggested by Claude"))
        assertTrue(options[0].text.contains("2 left"))
        assertTrue(options[0].text.contains("Expires in "))
        assertFalse(options[1].text.contains("Suggested by Claude"))
        assertFalse(options[2].isEnabled)
        assertTrue(options[2].text.contains("Only at a usage limit"))
        options[2].doClick()
        assertNull(panel.selectedChoice)
        assertTrue(labels(panel).any { it.contains("Disabled resets become usable") })
        options[1].doClick()
        assertEquals(ClaudeResetChoice.Grant(second), panel.selectedChoice)
        assertEquals(1, changes)
    }

    @Test
    fun sessionResetChooserExplainsItsNarrowScope() {
        val reset = ClaudeSessionReset(null)
        val panel = ClaudeResetSelectionPanel(ClaudeResets(sessionReset = reset))
        val button = visible(panel).filterIsInstance<JRadioButton>().single()
        assertNull(panel.selectedChoice)
        assertTrue(button.text.contains("Weekly limit still applies"))
        button.doClick()
        assertEquals(ClaudeResetChoice.Session(reset), panel.selectedChoice)
    }

    @Test
    fun manyResetsUseBoundedScrollableChooserAndListIndividualExpiries() {
        val resets =
            ClaudeResets(
                grants = (1..30).map { grant("grant-$it").copy(label = "Long grant $it") },
            )
        val panel = ClaudeResetSelectionPanel(resets)
        val scroll = panel.components.filterIsInstance<JScrollPane>().single()
        assertTrue(scroll.preferredSize.height <= com.intellij.util.ui.JBUI.scale(360))
        assertEquals(30, visible(panel).filterIsInstance<JRadioButton>().size)
        val tooltip = claudeResetsTooltip(resets)
        assertTrue(tooltip.contains("Reset 1: 5-hour limit · Expires in "))
        assertTrue(tooltip.contains("Reset 30: 5-hour limit · Expires in "))
        assertFalse(tooltip.contains("Long grant"))
    }

    @Test
    fun tooltipListsEachExpiryAndUsesTheChooserOrder() {
        val first = grant("first").copy(endsAt = Clock.System.now() + 4.days + 12.hours)
        val suggested = grant("suggested").copy(endsAt = Clock.System.now() + 12.days + 12.hours)
        val restricted = grant("restricted", usableNow = false).copy(endsAt = null)
        val resets = ClaudeResets(listOf(first, restricted, suggested), nextGrantId = suggested.id)
        val tooltip = claudeResetsTooltip(resets)
        assertTrue(tooltip.contains("Reset 1: 5-hour limit · Expires in 12d"), tooltip)
        assertTrue(tooltip.contains("Reset 2: 5-hour limit · Expires in 4d"), tooltip)
        assertTrue(tooltip.contains("Reset 3: 5-hour limit · Expiration unknown · At limit only"))
        val buttons = visible(ClaudeResetSelectionPanel(resets)).filterIsInstance<JRadioButton>()
        assertTrue(buttons[0].text.contains("Suggested by Claude"))
        assertTrue(buttons[1].text.contains("Reset 2:"))
        assertFalse(buttons[2].isEnabled)
    }

    @Test
    fun resetsUsableOnlyAtALimitAreListedWithoutALink() {
        val section = ClaudePopupSection()
        val resets = ClaudeResets(grants = listOf(grant("session_gift", usableNow = false)))

        section.update(ClaudeQuota(fiveHourUsage = window, resets = resets), null, true)

        assertTrue(links(section).isEmpty())
        assertTrue(labels(section).contains("Resets: 1"))
        assertTrue(labels(section).contains("Available at limit"))
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
        assertFalse(labels(section).any { it.startsWith("Resets:") })
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
