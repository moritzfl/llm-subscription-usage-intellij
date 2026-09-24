package de.moritzf.quota.idea

import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.ui.components.JBLabel
import de.moritzf.quota.idea.common.ProviderSnapshot
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageSnapshot
import de.moritzf.quota.idea.ui.popup.QuotaPopupScrollPane
import de.moritzf.quota.idea.ui.popup.RefreshablePopupPanel
import de.moritzf.quota.openai.OpenAiCodexQuota
import java.awt.Dimension
import java.awt.Point
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

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

    @Test
    fun tallPopupStaysInOneColumnAndScrollsVertically() {
        val stack = JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 0, true, false))
        val rows = List(20) { JPanel().apply { preferredSize = Dimension(200, 30) } }
        rows.forEach(stack::add)
        val scrollPane = QuotaPopupScrollPane(stack)

        scrollPane.fitHeight(180)
        scrollPane.size = scrollPane.preferredSize
        scrollPane.doLayout()
        scrollPane.viewport.doLayout()
        scrollPane.viewport.view.doLayout()
        stack.doLayout()

        assertEquals(180, scrollPane.preferredSize.height)
        assertTrue(scrollPane.verticalScrollBar.isVisible)
        assertEquals(scrollPane.viewport.width, scrollPane.viewport.view.width)
        assertEquals(rows.first().x, rows.last().x)
        assertTrue(rows.last().y > scrollPane.viewport.height)

        scrollPane.viewport.viewPosition = Point(0, rows.last().y)
        assertTrue(scrollPane.viewport.viewPosition.y > 0)

        rows.drop(3).forEach { it.isVisible = false }
        scrollPane.fitHeight(180)
        assertTrue(scrollPane.preferredSize.height < 180)
    }

    private fun openAiSnapshot(quota: OpenAiCodexQuota?, error: String?): QuotaUsageSnapshot {
        return QuotaUsageSnapshot(mapOf(QuotaProviderType.OPEN_AI to ProviderSnapshot(quota, error)))
    }
}
