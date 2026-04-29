package de.moritzf.quota.idea.ui.popup

import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.idea.ui.indicator.clampPercent
import de.moritzf.quota.ollama.OllamaQuota
import kotlin.math.roundToInt
import de.moritzf.quota.ollama.OllamaUsageWindow
import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.util.ui.JBUI
import javax.swing.JPanel

private const val OLLAMA_LABEL = "Ollama Cloud"

internal class OllamaPopupSection : JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 0, true, false)) {
    private val separator = createSeparatedBlock()
    private val errorLabel = createWarningLabel("").apply { border = JBUI.Borders.emptyTop(1) }
    private val titleLabel = createSectionTitleLabel(OLLAMA_LABEL, QuotaIcons.OLLAMA).apply { border = JBUI.Borders.emptyTop(0) }
    private val sessionBlock = WindowBlockPanel(3)
    private val weeklyBlock = WindowBlockPanel(5)

    init {
        isOpaque = false
        add(separator)
        add(errorLabel)
        add(titleLabel)
        add(sessionBlock)
        add(weeklyBlock)
        hideAll()
    }

    fun update(quota: OllamaQuota?, error: String?, visible: Boolean) {
        isVisible = visible
        if (!visible) return

        when {
            error != null -> {
                errorLabel.isVisible = true
                errorLabel.text = "Ollama error: $error"
                hideContent()
            }
            quota == null -> {
                hideAll()
                titleLabel.isVisible = true
                titleLabel.text = OLLAMA_LABEL
                sessionBlock.showLoading("Session")
                weeklyBlock.showLoading("Weekly")
            }
            else -> {
                errorLabel.isVisible = false
                val planTitle = quota.plan.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
                titleLabel.isVisible = true
                titleLabel.text = if (planTitle != null) "$OLLAMA_LABEL ($planTitle)" else OLLAMA_LABEL
                quota.sessionUsage?.let { sessionBlock.updateOllama(it, "Session") } ?: sessionBlock.clear()
                quota.weeklyUsage?.let { weeklyBlock.updateOllama(it, "Weekly") } ?: weeklyBlock.clear()
            }
        }
    }

    private fun hideAll() {
        errorLabel.isVisible = false
        hideContent()
    }

    private fun hideContent() {
        titleLabel.isVisible = false
        sessionBlock.isVisible = false
        weeklyBlock.isVisible = false
    }

    private fun WindowBlockPanel.updateOllama(window: OllamaUsageWindow, label: String) {
        val percent = clampPercent(window.usagePercent.roundToInt())
        val resetText = QuotaUiUtil.formatReset(window.resetsAt)
        var info = "$percent% used"
        if (resetText != null) info += " - $resetText"
        update("$label limit", info, percent)
    }
}
