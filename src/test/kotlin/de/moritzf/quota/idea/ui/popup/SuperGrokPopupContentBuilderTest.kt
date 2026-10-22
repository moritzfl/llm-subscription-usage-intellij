package de.moritzf.quota.idea.ui.popup

import com.intellij.ui.components.ActionLink
import de.moritzf.quota.supergrok.SuperGrokQuota
import de.moritzf.quota.supergrok.SuperGrokResetToken
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class SuperGrokPopupContentBuilderTest {
    @Test
    fun resetLinkListsEveryTokenExpiration() {
        val section = SuperGrokPopupSection()
        val now = Clock.System.now()
        val quota =
            SuperGrokQuota(
                resetTokens =
                    listOf(
                        SuperGrokResetToken("restok_1", now + 3.days),
                        SuperGrokResetToken("restok_2", now + 10.days),
                        SuperGrokResetToken("restok_3"),
                    )
            )

        section.update(quota, error = null, visible = true)

        val tooltip =
            section.components
                .filterIsInstance<JPanel>()
                .flatMap { it.components.filterIsInstance<ActionLink>() }
                .single()
                .toolTipText
        assertTrue(tooltip.contains("Use one SuperGrok weekly reset"))
        assertTrue(tooltip.contains("Reset 1: Expires in "))
        assertTrue(tooltip.contains("Reset 2: Expires in "))
        assertTrue(tooltip.contains("Reset 3: Expiration unknown"))
        assertFalse(tooltip.contains("restok_"))
    }

    @Test
    fun showsPayAsYouGoInDollarsWithBalanceAndProductBreakdown() {
        val section = SuperGrokPopupSection()
        section.update(
            SuperGrokQuota(
                creditUsage =
                    de.moritzf.quota.supergrok.SuperGrokUsageWindow(
                        label = "Weekly credits",
                        usagePercent = 100.0,
                    ),
                onDemandCap = 5000,
                onDemandUsed = 1000,
                prepaidBalance = 1446,
                productUsage =
                    listOf(
                        de.moritzf.quota.supergrok.SuperGrokProductUsage("GrokChat", 60.0),
                        de.moritzf.quota.supergrok.SuperGrokProductUsage("GrokBuild", 40.0),
                    ),
                autoTopUp = de.moritzf.quota.supergrok.SuperGrokAutoTopUp(2000, 500),
                resetTokens = listOf(SuperGrokResetToken("restok_1")),
            ),
            null,
            true,
        )
        val order =
            section.components
                .filter { it.isVisible }
                .map {
                    when {
                        it is JPanel && it.components.any { child -> child is ActionLink } ->
                            "reset"
                        it is WindowBlockPanel ->
                            it.components.filterIsInstance<javax.swing.JLabel>().first().text
                        it is javax.swing.JLabel -> it.text.substringBefore(":")
                        else -> null
                    }
                }
                .filterNotNull()
        assertEquals(
            listOf("Weekly credits", "reset", "Extra usage", "Credit balance"),
            order.dropWhile { it != "Weekly credits" },
            order.toString(),
        )

        val blocks = section.components.filterIsInstance<WindowBlockPanel>().filter { it.isVisible }
        val texts =
            blocks.flatMap { block ->
                block.components.filterIsInstance<javax.swing.JLabel>().map { it.text }
            } +
                section.components
                    .filterIsInstance<javax.swing.JLabel>()
                    .filter { it.isVisible }
                    .map { it.text }
        assertTrue("Extra usage" in texts, texts.toString())
        assertTrue("$10.00 of $50.00 used" in texts, texts.toString())
        assertTrue(
            "Credit balance: $14.46 • Auto top-up: $20.00 below $5.00" in texts,
            texts.toString(),
        )
        val tooltip =
            blocks.first().components.filterIsInstance<TruncatingPopupLabel>().last().detailTooltip
        assertTrue(
            tooltip!!.contains("Grok Chat: 60%") && tooltip.contains("Grok Build: 40%"),
            tooltip,
        )
    }

    @Test
    fun hidesResetLinkWhenNoTokensRemain() {
        val section = SuperGrokPopupSection()
        section.update(
            SuperGrokQuota(resetTokens = listOf(SuperGrokResetToken("restok_1"))),
            null,
            true,
        )

        section.update(SuperGrokQuota(), null, true)

        assertTrue(
            section.components
                .filterIsInstance<JPanel>()
                .flatMap { it.components.filterIsInstance<ActionLink>() }
                .isEmpty()
        )
    }
}
