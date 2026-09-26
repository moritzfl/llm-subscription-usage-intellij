package de.moritzf.quota.idea.settings

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.shared.DocumentModels
import java.awt.Color
import javax.swing.DefaultComboBoxModel

/** Settings combo for one account's document model. Vision rows show the cost and precision warning. */
internal class DocumentModelCombo(
    private val defaultModel: String,
    vision: Boolean,
) {
    val combo = ComboBox<String>().apply { prototypeDisplayValue = "mistral-ocr-latest" }
    val warning = JBLabel().apply {
        foreground = WARNING_COLOR
        isVisible = vision
        if (vision) text = warningHtml(DocumentModels.VISION_WARNING)
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

    companion object {
        val WARNING_COLOR: JBColor = JBColor(Color(0x8A6D00), Color(0xFFC107))

        fun warningHtml(text: String): String =
            "<html><body style='width:340px'>${QuotaUiUtil.escapeHtml(text)}</body></html>"
    }
}
