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
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JRadioButton
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
        assertTrue(tooltip.contains("Weekly reset: weekly limit · 2 left · Expires in "), tooltip)
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
    fun chooserGroupsResetsByKindAndRequiresSelection() {
        val resets = launchResets(nextGrantId = "fable-2")
        var changes = 0
        val panel = ClaudeResetSelectionPanel(resets) { changes++ }
        val options = visible(panel).filterIsInstance<JRadioButton>()

        assertEquals(
            listOf("Full reset", "Fable reset · 3 left", "Weekly reset"),
            options.map { it.text },
        )
        assertNull(panel.selectedChoice)
        val comments = comments(panel)
        assertTrue(
            comments.any { "Refills your 5-hour and weekly limits. Expires in 26d" in it },
            comments.toString(),
        )
        assertTrue(
            comments.any {
                "Refills your 5-hour and Fable limits and returns what Fable used to your weekly " +
                    "limit. Your weekly limit is not fully reset. All expire in 115d" in it
            },
            comments.toString(),
        )
        assertTrue(comments.any { "Usable only at a usage limit · Expires in " in it })
        assertTrue(options[1].toolTipText.contains("Launch &lt;1&gt; · Expires in 115d"))
        assertTrue(options[1].toolTipText.contains("Launch &lt;3&gt;"))

        assertFalse(options[2].isEnabled)
        options[2].doClick()
        assertNull(panel.selectedChoice)
        options[1].doClick()
        val choice = panel.selectedChoice as ClaudeResetChoice.Grant
        assertEquals("fable-2", choice.grant.id, "the server's next grant is claimed first")
        assertEquals(3, choice.resetsLeft)
        assertEquals(1, changes)
    }

    @Test
    fun onlyTheKindThatRunsOutFirstIsMarked() {
        val panel = ClaudeResetSelectionPanel(launchResets())
        val components = visible(panel)
        val options = components.filterIsInstance<JRadioButton>()
        val badge = components.single { it is JLabel && it.text == "Expires first" }
        assertTrue(
            components.indexOf(badge) in
                components.indexOf(options[0])..components.indexOf(options[1]),
        )

        val fableOnly = ClaudeResets(launchResets().grants.filter { it.givesBackWeekly })
        assertFalse(labels(ClaudeResetSelectionPanel(fableOnly)).contains("Expires first"))
        val endsAt = Clock.System.now() + 5.days
        val tied =
            ClaudeResets(
                launchResets().grants.filter { it.usableNow }.map { it.copy(endsAt = endsAt) },
            )
        assertFalse(labels(ClaudeResetSelectionPanel(tied)).contains("Expires first"))
    }

    @Test
    fun groupedResetsClaimTheEarliestExpiryAndSayWhenExpiriesDiffer() {
        val later = grant("later").copy(endsAt = Clock.System.now() + 12.days + 12.hours)
        val sooner = grant("sooner").copy(endsAt = Clock.System.now() + 4.days + 12.hours)
        val resets = ClaudeResets(listOf(later, sooner))

        assertEquals(ClaudeResetChoice.Grant(listOf(sooner, later)), resets.choices().single())
        assertTrue(comments(ClaudeResetSelectionPanel(resets)).any { "First expires in 4d" in it })
        assertTrue(
            claudeResetsTooltip(resets)
                .contains("5-hour reset: 5-hour limit · 2 left · First expires in 4d"),
        )
    }

    @Test
    fun sessionResetChooserExplainsItsNarrowScope() {
        val reset = ClaudeSessionReset(null)
        val panel = ClaudeResetSelectionPanel(ClaudeResets(sessionReset = reset))
        val button = visible(panel).filterIsInstance<JRadioButton>().single()
        assertNull(panel.selectedChoice)
        assertEquals("Session reset", button.text)
        assertTrue(
            comments(panel).any {
                "Refills your 5-hour limit. Your weekly limit still applies. One per week" in it
            },
        )
        button.doClick()
        assertEquals(ClaudeResetChoice.Session(reset), panel.selectedChoice)
    }

    @Test
    fun tooltipNamesEachKindInChooserOrderWithoutGrantNames() {
        val tooltip = claudeResetsTooltip(launchResets())
        val full = tooltip.indexOf("Full reset: 5-hour and weekly limits · Expires in 26d")
        val fable =
            tooltip.indexOf("Fable reset: 5-hour and Fable limits · 3 left · All expire in 115d")
        val weekly = tooltip.indexOf("Weekly reset: weekly limit · Expires in ")
        assertTrue(full in 0 until fable && fable < weekly, tooltip)
        assertTrue(tooltip.contains("At limit only"))
        assertFalse(tooltip.contains("Launch"))
    }

    @Test
    fun refilledLimitsFollowWhatEachGrantClears() {
        assertEquals("5-hour limit", refilledLimits(grant("a")))
        assertEquals("Opus limit", refilledLimits(grant("b", clears = listOf("seven_day_opus"))))
        assertEquals(
            "weekly limit",
            refilledLimits(grant("c", clears = listOf("seven_day_overage_included"))),
        )
        assertEquals(
            "usage limits",
            refilledLimits(grant("d", clears = listOf("seven_day_cowork"))),
        )
        assertEquals(
            "5-hour, weekly and Sonnet limits",
            refilledLimits(
                grant("e", clears = listOf("seven_day_sonnet", "seven_day", "five_hour")),
            ),
        )
        val fable =
            grant("f", clears = listOf("five_hour", "seven_day_overage_included"))
                .copy(givesBackWeekly = true)
        assertEquals("5-hour and Fable limits", refilledLimits(fable))
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

    /** A full reset, three Fable launch resets, and a weekly reset usable only at a limit. */
    private fun launchResets(nextGrantId: String? = null): ClaudeResets {
        val now = Clock.System.now()
        val full =
            grant("full", clears = listOf("five_hour", "seven_day", "seven_day_overage_included"))
                .copy(
                    label = "One full limit reset",
                    endsAt = now + 26.days + 12.hours,
                    type = "full",
                )
        val fable =
            (1..3).map {
                grant("fable-$it", clears = listOf("five_hour", "seven_day_overage_included"))
                    .copy(
                        label = "Launch <$it>",
                        endsAt = now + 115.days + 12.hours,
                        givesBackWeekly = true,
                        type = "full",
                    )
            }
        val restricted = grant("restricted", usableNow = false, clears = listOf("seven_day"))
        return ClaudeResets(fable + restricted + full, nextGrantId)
    }

    private fun comments(root: Container): List<String> =
        visible(root).filterIsInstance<JEditorPane>().map {
            it.document.getText(0, it.document.length).trim().replace(Regex("\\s+"), " ")
        }

    private fun links(root: Container): List<ActionLink> =
        visible(root).filterIsInstance<ActionLink>()

    private fun labels(root: Container): List<String> =
        visible(root).filter { it is JLabel && it !is ActionLink }.map { (it as JLabel).text }

    private fun visible(root: Container): List<java.awt.Component> =
        root.components
            .filter { it.isVisible }
            .flatMap { listOf(it) + ((it as? Container)?.let(::visible) ?: emptyList()) }
}
