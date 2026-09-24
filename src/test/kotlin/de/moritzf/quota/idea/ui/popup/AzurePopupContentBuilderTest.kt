package de.moritzf.quota.idea.ui.popup

import de.moritzf.quota.azure.AzureAccountIdentity
import de.moritzf.quota.azure.AzureQuota
import de.moritzf.quota.azure.AzureResourceRef
import de.moritzf.quota.azure.AzureUsageWindow
import de.moritzf.quota.azure.parseAzureDeployments
import de.moritzf.quota.idea.ui.indicator.AzureUi
import javax.swing.JLabel
import javax.swing.JProgressBar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

class AzurePopupContentBuilderTest {
    @Test
    fun showsAccountAndDiscoveredDeploymentsWithoutAllocationQuotasOrCapacity() {
        val west = assertNotNull(parseAzureDeployments(
            """{"value":[
                {"name":"chat-production","sku":{"name":"DataZoneStandard","capacity":3333},"properties":{"model":{"name":"gpt-6-sol"}}},
                {"name":"chat-testing","sku":{"name":"DataZoneStandard","capacity":3333},"properties":{"model":{"name":"gpt-6-sol"}}},
                {"name":"embeddings","sku":{"name":"DataZoneStandard","capacity":2000},"properties":{"model":{"name":"text-embedding-3-large"}}}
            ]}""",
            AzureResourceRef("ai-west", "westeurope", null, null),
        ))
        val east = assertNotNull(parseAzureDeployments(
            """{"value":[{"name":"chat-east","properties":{"model":{"name":"gpt-4.1"}}}]}""",
            AzureResourceRef("ai-east", "eastus", null, null),
        ))
        val quota = AzureQuota(
            account = AzureAccountIdentity(userName = "alex", subscriptionName = "Work subscription"),
            windows = listOf(
                AzureUsageWindow("tokens", "Tokens / minute", AzureUsageWindow.ALLOCATION, used = 3333.0, limit = 3333.0),
            ) + west.take(1) + east + west.drop(1),
            models = listOf("not-a-discovered-deployment"),
        )
        val section = AzurePopupSection()

        section.update(quota, error = null, visible = true)

        assertEquals(
            listOf(
                listOf("Work subscription", "User: alex"),
                listOf("Resource: ai-west", "Region: westeurope"),
                listOf("gpt-6-sol"),
                listOf("text-embedding-3-large"),
                listOf("Resource: ai-east", "Region: eastus"),
                listOf("gpt-4.1"),
            ),
            section.visibleLabels(),
        )
        assertTrue(section.visibleBlocks()[1].components.filterIsInstance<JLabel>().first().font.isBold)
        assertTrue(section.visibleBlocks()[2].components.filterIsInstance<JLabel>().first().font.isPlain)
        assertTrue(section.visibleBlocks().all { block -> block.components.filterIsInstance<JProgressBar>().none { it.isVisible } })
        assertEquals("alex", AzureUi.barText(quota, null))
        assertEquals(-1, AzureUi.displayPercent(quota, null))

        section.update(quota.copy(windows = east), error = null, visible = true)
        assertEquals(
            listOf(
                listOf("Work subscription", "User: alex"),
                listOf("Resource: ai-east", "Region: eastus"),
                listOf("gpt-4.1"),
            ),
            section.visibleLabels(),
        )
        section.update(quota.copy(account = null, windows = east), error = null, visible = true)
        assertTrue(section.visibleBlocks()[1].components.filterIsInstance<JLabel>().first().font.isPlain)
        section.update(quota.copy(windows = east), error = null, visible = true)
        assertTrue(section.visibleBlocks()[1].components.filterIsInstance<JLabel>().first().font.isBold)
    }

    @Test
    fun onlyObservedLiveRateLimitShowsAProgressBar() {
        val live = AzureUsageWindow(
            "chat", "chat", AzureUsageWindow.LIVE,
            limit = 100.0, remaining = 25.0, unit = "tokens", expiresAt = Clock.System.now() + 1.minutes,
        )
        val section = AzurePopupSection()
        section.update(AzureQuota(windows = listOf(live)), error = null, visible = true)

        val block = section.visibleBlocks().single()
        assertEquals(
            listOf("Live rate limit: chat", "25 remaining of 100 tokens"),
            block.components.filterIsInstance<JLabel>().map { it.text },
        )
        assertEquals(75, block.components.filterIsInstance<JProgressBar>().single().value)
        assertTrue(block.components.filterIsInstance<JProgressBar>().single().isVisible)
        assertEquals(75, AzureUi.displayPercent(AzureQuota(windows = listOf(live)), null))
    }

    private fun AzurePopupSection.visibleBlocks(): List<WindowBlockPanel> =
        components.filterIsInstance<WindowBlockPanel>().filter { it.isVisible }

    private fun AzurePopupSection.visibleLabels(): List<List<String>> = visibleBlocks().map { block ->
        block.components.filterIsInstance<JLabel>().filter { it.isVisible }.map { it.text }
    }
}
