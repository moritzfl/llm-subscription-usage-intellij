package de.moritzf.quota.idea.settings

import com.intellij.openapi.ui.ComboBox
import de.moritzf.quota.shared.DocumentModels
import java.awt.event.ItemEvent
import javax.swing.DefaultComboBoxModel

/**
 * Settings combo for one account's vision model. "-" (the first entry) keeps vision off;
 * every provider starts there because image analysis is opt-in.
 */
internal class VisionModelCombo {
    val combo = ComboBox<String>().apply { prototypeDisplayValue = "mistral-ocr-latest" }

    init {
        combo.addItemListener { event ->
            if (event.stateChange == ItemEvent.SELECTED) combo.toolTipText = tooltip()
        }
    }

    fun selected(): String? = combo.selectedItem as? String

    fun storedValue(): String? = DocumentModels.storedSelection(selected(), DocumentModels.OFF)

    fun differs(saved: String?): Boolean = DocumentModels.differs(selected(), saved, DocumentModels.OFF)

    fun show(saved: String?, choices: List<String>) {
        val models = DocumentModels.withOff(choices)
        if ((0 until combo.itemCount).map(combo::getItemAt) != models) {
            combo.model = DefaultComboBoxModel(models.toTypedArray())
        }
        val selected = saved?.trim()?.takeIf { it in models } ?: DocumentModels.OFF
        if (combo.selectedItem != selected) combo.selectedItem = selected
        combo.toolTipText = tooltip()
    }

    private fun tooltip(): String {
        return if (selected() == DocumentModels.OFF) {
            "Vision is off. Pick a model so subscription_vision and the vision test can use this account."
        } else {
            "Model used by subscription_vision when the tool call passes no model."
        }
    }
}
