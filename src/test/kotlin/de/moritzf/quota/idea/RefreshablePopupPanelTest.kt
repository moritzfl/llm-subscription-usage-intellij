package de.moritzf.quota.idea

import com.intellij.ui.components.JBLabel
import de.moritzf.quota.idea.common.ProviderSnapshot
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageSnapshot
import de.moritzf.quota.idea.ui.popup.RefreshablePopupPanel
import de.moritzf.quota.openai.OpenAiCodexQuota
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class RefreshablePopupPanelTest {
    @Test
    fun refreshUpdatesContentInPlace() {
        val label = JBLabel("empty")
        val panel = RefreshablePopupPanel<QuotaUsageSnapshot>(label) { state ->
            val snapshot = state[QuotaProviderType.OPEN_AI]
            label.text = snapshot.error ?: (snapshot.quota as? OpenAiCodexQuota)?.planType ?: "empty"
        }

        panel.refresh(openAiSnapshot(quota = null, error = "Loading usage data..."))
        assertEquals("Loading usage data...", label.text)

        panel.refresh(openAiSnapshot(quota = OpenAiCodexQuota(planType = "pro"), error = null))

        // The same label component should still be there, updated in-place.
        assertSame(label, panel.getComponent(0))
        assertEquals("pro", label.text)
    }

    private fun openAiSnapshot(quota: OpenAiCodexQuota?, error: String?): QuotaUsageSnapshot {
        return QuotaUsageSnapshot(mapOf(QuotaProviderType.OPEN_AI to ProviderSnapshot(quota, error)))
    }
}
