package de.moritzf.quota.idea.settings

import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VisionModelComboTest {
    @Test
    fun unverifiedModelsRemainSelectableAfterDeclaredVisionModels() {
        SwingUtilities.invokeAndWait {
            val picker = VisionModelCombo(groupUnverified = true)
            picker.show(
                "working-but-undeclared",
                listOf("declared"),
                listOf("declared", "working-but-undeclared"),
            )
            assertEquals(
                listOf("-", "declared", "working-but-undeclared"),
                (0 until picker.combo.itemCount).map(picker.combo::getItemAt),
            )
            assertEquals("working-but-undeclared", picker.selected())
            assertTrue(picker.combo.toolTipText.contains("not declared"))
            assertFalse(picker.combo.isSwingPopup)

            picker.show("working-but-undeclared", listOf("declared", "working-but-undeclared"))
            assertFalse(picker.combo.toolTipText.contains("not declared"))
            assertEquals("working-but-undeclared", picker.selected())
        }
    }

    @Test
    fun noDeclaredVisionModelsStillAllowsOptInToUnverifiedModels() {
        SwingUtilities.invokeAndWait {
            val picker = VisionModelCombo(groupUnverified = true)
            picker.show(null, emptyList(), listOf("unverified"))
            assertEquals("-", picker.selected())
            assertEquals(
                listOf("-", "unverified"),
                (0 until picker.combo.itemCount).map(picker.combo::getItemAt),
            )
            picker.combo.selectedItem = "unverified"
            assertEquals("unverified", picker.storedValue())
        }
    }
}
