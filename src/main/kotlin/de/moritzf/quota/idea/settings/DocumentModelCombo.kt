package de.moritzf.quota.idea.settings

import com.intellij.openapi.ui.ComboBox
import de.moritzf.quota.shared.DocumentModels
import javax.swing.DefaultComboBoxModel

/** Settings combo for one account's document model. Vision rows keep the explainer on a warning icon. */
internal class DocumentModelCombo(
    private val defaultModel: String,
    vision: Boolean,
) {
    val combo = ComboBox<String>().apply { prototypeDisplayValue = "mistral-ocr-latest" }
    val warning = DocumentWarningIcon().apply {
        if (vision) setExplainer("Not a document or OCR model", DocumentModels.VISION_WARNING)
    }

    fun selected(): String? = combo.selectedItem as? String

    fun storedValue(): String? = DocumentModels.storedSelection(selected(), defaultModel)

    fun differs(saved: String?): Boolean = DocumentModels.differs(selected(), saved, defaultModel)

    fun show(saved: String?, choices: List<String>) {
        val models = choices.ifEmpty { listOf(defaultModel) }
        if ((0 until combo.itemCount).map(combo::getItemAt) != models) {
            combo.model = DefaultComboBoxModel(models.toTypedArray())
        }
        val selected = saved?.trim()?.takeIf { it in models } ?: defaultModel.takeIf { it in models } ?: models.first()
        if (combo.selectedItem != selected) combo.selectedItem = selected
    }
}
