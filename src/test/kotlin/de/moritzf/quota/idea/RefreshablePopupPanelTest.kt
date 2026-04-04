package de.moritzf.quota.idea

import com.intellij.ui.components.JBLabel
import de.moritzf.quota.OpenAiCodexQuota
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class RefreshablePopupPanelTest {
    @Test
    fun refreshReplacesRenderedContent() {
        val panel = RefreshablePopupPanel<QuotaPopupContentState> { state ->
            JBLabel(state.error ?: state.quota?.planType ?: "empty")
        }

        panel.refresh(QuotaPopupContentState(quota = null, error = "Loading usage data..."))
        val firstLabel = panel.getComponent(0) as JBLabel
        assertEquals("Loading usage data...", firstLabel.text)

        panel.refresh(QuotaPopupContentState(quota = OpenAiCodexQuota(planType = "pro"), error = null))
        val secondLabel = panel.getComponent(0) as JBLabel

        assertNotSame(firstLabel, secondLabel)
        assertEquals("pro", secondLabel.text)
    }
}
