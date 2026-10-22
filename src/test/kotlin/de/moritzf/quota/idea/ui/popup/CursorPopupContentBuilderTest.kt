package de.moritzf.quota.idea.ui.popup

import de.moritzf.quota.cursor.CursorPlanUsage
import de.moritzf.quota.cursor.CursorQuota
import de.moritzf.quota.cursor.CursorQuotaClient
import de.moritzf.quota.cursor.CursorRequestUsage
import de.moritzf.quota.idea.ui.indicator.cursorIndicatorState
import javax.swing.JLabel
import javax.swing.JProgressBar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CursorPopupContentBuilderTest {
    @Test
    fun displaysModelPoolsWithoutFalseExhaustionFromLegacyCounters() {
        // cursor.com/docs/models-and-pricing; reported dollar/pool mismatch: quotio issue #519.
        val quota =
            CursorQuotaClient.parseUsageSummary(
                """{
              "autoModelSelectedDisplayMessage":"You've hit your usage limit",
              "individualUsage":{"plan":{
                "used":2000,"limit":2000,"autoPercentUsed":22,"apiPercentUsed":3,"totalPercentUsed":100
              }}
            }""",
                null,
                """{"gpt-4":{"numRequestsTotal":500,"maxRequestUsage":500}}""",
            )
        val section = CursorPopupSection()
        section.update(quota, null, true)

        val blocks = section.visibleBlocks()
        assertEquals(listOf("Cursor Models usage", "Other Models usage"), blocks.map { it.title() })
        assertEquals(
            listOf(22, 3),
            blocks.map { it.components.filterIsInstance<JProgressBar>().single().value },
        )
        assertEquals(emptyList(), section.warnings())
        assertEquals(22, cursorIndicatorState(quota)?.percent)
        blocks.forEach { assertTrue(it.components.filterIsInstance<JLabel>().first().font.isBold) }
    }

    @Test
    fun keepsBothEqualPoolsAndNamesOnlyExhaustedPool() {
        val section = CursorPopupSection()
        section.update(
            CursorQuota(planUsage = CursorPlanUsage(autoPercentUsed = 25.0, apiPercentUsed = 25.0)),
            null,
            true,
        )
        assertEquals(
            listOf("Cursor Models usage", "Other Models usage"),
            section.visibleBlocks().map { it.title() },
        )

        section.update(
            CursorQuota(
                planUsage = CursorPlanUsage(autoPercentUsed = 25.0, apiPercentUsed = 100.0)
            ),
            null,
            true,
        )
        assertEquals(listOf("Other Models limit reached"), section.warnings())
    }

    @Test
    fun clearsMissingPoolsAndRetainsLegacyRequests() {
        val section = CursorPopupSection()
        section.update(
            CursorQuota(
                planUsage = CursorPlanUsage(autoPercentUsed = 25.0, apiPercentUsed = 100.0)
            ),
            null,
            true,
        )
        section.update(CursorQuota(planUsage = CursorPlanUsage(autoPercentUsed = 0.0)), null, true)
        assertEquals(listOf("Cursor Models usage"), section.visibleBlocks().map { it.title() })
        assertEquals(emptyList(), section.warnings())

        section.update(
            CursorQuota(requestUsage = CursorRequestUsage(used = 100, limit = 100)),
            null,
            true,
        )
        assertEquals(listOf("Requests"), section.visibleBlocks().map { it.title() })
        assertEquals(listOf("Cursor limit reached"), section.warnings())
        assertFalse(section.visibleBlocks().any { it.title().contains("Models") })
    }

    @Test
    fun showsTheGrokBotAllowanceAndTheWeeklyIncludedReset() {
        val section = CursorPopupSection()
        val reset = kotlin.time.Clock.System.now() + kotlin.time.Duration.parse("2d")
        section.update(
            CursorQuota(
                planUsage =
                    CursorPlanUsage(
                        autoPercentUsed = 40.0,
                        billingCycleEnd = reset + kotlin.time.Duration.parse("20d"),
                        weeklyResetsAt = reset,
                    ),
                grokUsage = de.moritzf.quota.cursor.CursorGrokUsage(60.0, reset),
            ),
            null,
            true,
        )

        val blocks = section.visibleBlocks()
        assertEquals(listOf("Cursor Models usage", "Grok Bot weekly"), blocks.map { it.title() })
        val labels = blocks.flatMap { block ->
            block.components.filterIsInstance<JLabel>().map { it.text }
        }
        assertTrue(
            labels.any {
                it.startsWith("40% used - Resets in 1d") || it.startsWith("40% used - Resets in 2d")
            },
            labels.toString(),
        )
        assertTrue(labels.any { it.startsWith("60% used - Resets in ") }, labels.toString())

        section.update(
            CursorQuota(
                planUsage = CursorPlanUsage(autoPercentUsed = 40.0),
                grokUsage = de.moritzf.quota.cursor.CursorGrokUsage(100.0, trialEndsAt = reset),
            ),
            null,
            true,
        )
        val trial =
            section.visibleBlocks().last().components.filterIsInstance<JLabel>().map { it.text }
        assertTrue(trial.any { it.startsWith("100% used - Trial: Expires in ") }, trial.toString())
    }

    @Test
    fun extraUsageComesLastAfterTheIncludedAllowances() {
        val section = CursorPopupSection()
        section.update(
            CursorQuota(
                planUsage = CursorPlanUsage(autoPercentUsed = 40.0),
                onDemandUsage =
                    de.moritzf.quota.cursor.CursorOnDemandUsage(usedUsd = 12.5, limitUsd = 50.0),
                teamOnDemandUsage =
                    de.moritzf.quota.cursor.CursorOnDemandUsage(usedUsd = 100.0, limitUsd = 100.0),
                grokUsage = de.moritzf.quota.cursor.CursorGrokUsage(10.0),
            ),
            null,
            true,
        )

        assertEquals(
            listOf("Cursor Models usage", "Grok Bot weekly", "Extra usage", "Extra usage (team)"),
            section.visibleBlocks().map { it.title() },
        )
        assertTrue(
            section.visibleBlocks()[2].components.filterIsInstance<JLabel>().any {
                it.text.startsWith("\$12.50 of \$50.00 used")
            }
        )
        assertEquals(listOf("Cursor team extra usage limit reached"), section.warnings())
    }

    private fun CursorPopupSection.visibleBlocks(): List<WindowBlockPanel> =
        components.filterIsInstance<WindowBlockPanel>().filter { it.isVisible }

    private fun WindowBlockPanel.title(): String =
        components.filterIsInstance<JLabel>().first().text

    private fun CursorPopupSection.warnings(): List<String> =
        components
            .filterIsInstance<TruncatingPopupLabel>()
            .filter { it.isVisible && it.text.contains("limit", ignoreCase = true) }
            .map { it.text }
}
