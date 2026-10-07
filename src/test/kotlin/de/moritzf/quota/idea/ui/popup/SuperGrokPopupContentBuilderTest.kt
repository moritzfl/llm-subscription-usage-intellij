package de.moritzf.quota.idea.ui.popup

import com.intellij.ui.components.ActionLink
import de.moritzf.quota.supergrok.SuperGrokQuota
import de.moritzf.quota.supergrok.SuperGrokResetToken
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class SuperGrokPopupContentBuilderTest {
    @Test
    fun resetLinkListsEveryTokenExpiration() {
        val section = SuperGrokPopupSection()
        val now = Clock.System.now()
        val quota = SuperGrokQuota(
            resetTokens = listOf(
                SuperGrokResetToken("restok_1", now + 3.days),
                SuperGrokResetToken("restok_2", now + 10.days),
                SuperGrokResetToken("restok_3"),
            ),
        )

        section.update(quota, error = null, visible = true)

        val tooltip = section.components.filterIsInstance<JPanel>()
            .flatMap { it.components.filterIsInstance<ActionLink>() }.single().toolTipText
        assertTrue(tooltip.contains("Redeem one SuperGrok weekly reset"))
        assertTrue(tooltip.contains("Token 1: Expires in "))
        assertTrue(tooltip.contains("Token 2: Expires in "))
        assertTrue(tooltip.contains("Token 3: Expiration unknown"))
        assertFalse(tooltip.contains("restok_"))
    }

    @Test
    fun hidesResetLinkWhenNoTokensRemain() {
        val section = SuperGrokPopupSection()
        section.update(SuperGrokQuota(resetTokens = listOf(SuperGrokResetToken("restok_1"))), null, true)

        section.update(SuperGrokQuota(), null, true)

        assertTrue(section.components.filterIsInstance<JPanel>()
            .flatMap { it.components.filterIsInstance<ActionLink>() }.isEmpty())
    }
}
