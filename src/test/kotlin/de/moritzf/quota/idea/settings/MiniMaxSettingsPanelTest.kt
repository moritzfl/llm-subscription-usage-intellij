package de.moritzf.quota.idea.settings

import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.minimax.MiniMaxVisionClient
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MiniMaxSettingsPanelTest {
    @Test
    fun savesTheSelectedVisionModel() = onEdt {
        val panel = MiniMaxSettingsPanel({ null })
        val comboField =
            MiniMaxSettingsPanel::class.java.getDeclaredField("visionModelCombo").apply {
                isAccessible = true
            }
        (comboField.get(panel) as VisionModelCombo).show(
            MiniMaxVisionClient.MODEL,
            listOf(MiniMaxVisionClient.MODEL),
        )
        val account =
            ProviderAccount(
                id = QuotaProviderType.MINIMAX.id,
                typeId = QuotaProviderType.MINIMAX.id,
                name = "MiniMax",
            )
        val saved = account.snapshot()

        panel.writeAccountFields(account)

        assertEquals(
            MiniMaxVisionClient.MODEL,
            account.extra(ProviderAccount.EXTRA_VISION_MODEL),
        )
        assertTrue(panel.accountFieldsModified(account, saved, QuotaSettingsState()))
    }

    private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
}
