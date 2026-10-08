package de.moritzf.quota.idea.settings

import de.moritzf.quota.idea.mcp.DocumentToMarkdownProvider
import de.moritzf.quota.idea.mcp.VisionProvider
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelTestButtonTest {
    @Test
    fun documentTestTracksLoadedSelectionWithoutManualModelSwitch() {
        SwingUtilities.invokeAndWait {
            val combo = JComboBox<String>()
            val button = DocumentTestButton(
                DocumentToMarkdownProvider.AZURE,
                { (combo.selectedItem as? String).orEmpty() },
                { null },
                combo,
            )
            assertFalse(button.isEnabled)

            combo.model = DefaultComboBoxModel(arrayOf("mistral-ocr-4-0", "-"))
            assertTrue(button.isEnabled)

            combo.model = DefaultComboBoxModel(arrayOf("-", "mistral-ocr-4-0"))
            assertFalse(button.isEnabled)

            combo.selectedItem = "mistral-ocr-4-0"
            assertTrue(button.isEnabled)
            combo.selectedItem = "-"
            assertFalse(button.isEnabled)

            combo.model = DefaultComboBoxModel(arrayOf("mistral-ocr-4-0"))
            assertTrue(button.isEnabled)
            combo.model = DefaultComboBoxModel()
            assertFalse(button.isEnabled)
        }
    }

    @Test
    fun visionTestDisablesWhenDiscoveryReplacesSelectionWithOff() {
        SwingUtilities.invokeAndWait {
            val picker = VisionModelCombo()
            val button = VisionTestButton(VisionProvider.OPEN_AI, picker) { null }
            picker.show("vision-model", listOf("vision-model"))
            assertTrue(button.isEnabled)

            picker.show(null, listOf("another-model"))
            assertFalse(button.isEnabled)

            picker.show("another-model", listOf("another-model"))
            assertTrue(button.isEnabled)
        }
    }
}
