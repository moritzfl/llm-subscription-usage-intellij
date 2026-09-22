package de.moritzf.quota.idea.ui.popup

import de.moritzf.quota.antigravity.AntigravityQuota
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.shared.ProviderQuota
import kotlin.math.roundToInt

internal class AntigravityPopupSection : ProviderPopupSection() {
    private val titleLabel = createSectionTitleLabel("Antigravity", QuotaIcons.ANTIGRAVITY)
    private val errorLabel = createWarningLabel("")
    private val blocks = mutableListOf<WindowBlockPanel>()

    init {
        isOpaque = false
        add(createSeparatedBlock())
        add(titleLabel)
        add(errorLabel)
        errorLabel.isVisible = false
    }

    override fun update(quota: ProviderQuota?, error: String?, visible: Boolean) {
        isVisible = visible
        if (!visible) return
        titleLabel.text = sectionTitle("Antigravity")
        val warning = error ?: (quota as? AntigravityQuota)?.warnings?.joinToString(" ")?.takeIf { it.isNotEmpty() }
        errorLabel.text = warning.orEmpty()
        errorLabel.isVisible = warning != null
        blocks.forEach { it.clear() }
        if (error == null && quota is AntigravityQuota) {
            for ((index, window) in quota.windows.withIndex()) {
                val label = if (window.window == "weekly") "Weekly" else window.label
                val title = "${window.group} / $label"
                val percent = window.usagePercent?.roundToInt()
                val info = buildList {
                    add(when {
                        window.disabled -> "Unavailable"
                        percent == null -> "Usage unavailable"
                        else -> "$percent% used"
                    })
                    QuotaUiUtil.formatReset(window.resetsAt)?.let(::add)
                }.joinToString(" - ")
                block(index).apply {
                    if (percent != null) update(title, info, percent) else showUnavailable(title, info)
                }
            }
        } else if (error == null) {
            block(0).showLoading("Quota")
        }
        revalidate()
        repaint()
    }

    private fun block(index: Int): WindowBlockPanel {
        while (blocks.size <= index) {
            val block = WindowBlockPanel(if (blocks.isEmpty()) 3 else 5)
            blocks += block
            add(block)
        }
        return blocks[index]
    }
}
