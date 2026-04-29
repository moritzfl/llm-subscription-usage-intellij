package de.moritzf.quota.idea

import com.intellij.ui.components.JBLabel
import de.moritzf.quota.idea.ui.popup.QuotaPopupContentState
import de.moritzf.quota.idea.ui.popup.RefreshablePopupPanel
import de.moritzf.quota.openai.OpenAiCodexQuota
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class RefreshablePopupPanelTest {
    @Test
    fun refreshUpdatesContentInPlace() {
        val label = JBLabel("empty")
        val panel = RefreshablePopupPanel<QuotaPopupContentState>(label) { state ->
            label.text = state.error ?: state.quota?.planType ?: "empty"
        }

        panel.refresh(QuotaPopupContentState(quota = null, error = "Loading usage data..."))
        assertEquals("Loading usage data...", label.text)

        panel.refresh(QuotaPopupContentState(quota = OpenAiCodexQuota(planType = "pro"), error = null))

        // The same label component should still be there, updated in-place.
        assertSame(label, panel.getComponent(0))
        assertEquals("pro", label.text)
    }
}
