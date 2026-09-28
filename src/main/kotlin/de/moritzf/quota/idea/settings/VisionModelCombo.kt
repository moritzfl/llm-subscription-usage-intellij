package de.moritzf.quota.idea.settings

import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.ui.GroupedComboBoxRenderer
import de.moritzf.quota.shared.DocumentModels
import java.awt.event.ItemEvent
import javax.swing.DefaultComboBoxModel

/**
 * Settings combo for one account's vision model. "-" (the first entry) keeps vision off;
 * every provider starts there because image analysis is opt-in.
 */
internal class VisionModelCombo(groupUnverified: Boolean = false) {
    val combo = ComboBox<String>().apply { prototypeDisplayValue = "mistral-ocr-latest" }
    private var groupHeaders: Map<String, ListSeparator> = emptyMap()
    private var unverifiedModels: Set<String> = emptySet()

    init {
        if (groupUnverified) {
            combo.setSwingPopup(false)
            combo.renderer = object : GroupedComboBoxRenderer<String>(combo) {
                override fun getText(item: String): String = item
                override fun separatorFor(value: String): ListSeparator? = groupHeaders[value]
            }
        }
        combo.addItemListener { event ->
            if (event.stateChange == ItemEvent.SELECTED) combo.toolTipText = tooltip()
        }
    }

    fun selected(): String? = combo.selectedItem as? String

    fun storedValue(): String? = DocumentModels.storedSelection(selected(), DocumentModels.OFF)

    fun differs(saved: String?): Boolean = DocumentModels.differs(selected(), saved, DocumentModels.OFF)

    fun show(saved: String?, choices: List<String>, unverified: List<String> = emptyList()) {
        val declared = choices.filter { it != DocumentModels.OFF }.distinct()
        unverifiedModels = unverified.filter { it != DocumentModels.OFF && it !in declared }.toSet()
        groupHeaders = buildMap {
            declared.firstOrNull()?.let { put(it, ListSeparator("Declared vision support")) }
            unverifiedModels.firstOrNull()?.let { put(it, ListSeparator("Unverified vision support")) }
        }
        val models = DocumentModels.withOff(declared + unverifiedModels)
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
        } else if (selected() in unverifiedModels) {
            "Image support is not declared by the console. Use Test vision before relying on this model."
        } else {
            "Model used by subscription_vision when the tool call passes no model."
        }
    }
}
