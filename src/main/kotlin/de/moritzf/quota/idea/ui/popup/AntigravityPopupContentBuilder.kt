package de.moritzf.quota.idea.ui.popup

import com.intellij.util.ui.JBUI
import de.moritzf.quota.antigravity.AntigravityQuota
import de.moritzf.quota.idea.common.QuotaBundle.message
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.shared.ProviderQuota
import kotlin.math.roundToInt

internal class AntigravityPopupSection : ProviderPopupSection() {
    private val titleLabel = createSectionTitleLabel("Antigravity", QuotaIcons.ANTIGRAVITY)
    private val errorLabel = createWarningLabel("")
    private val creditsLabel = createMutedLabel("").apply { border = JBUI.Borders.emptyTop(2) }
    private val blocks = mutableListOf<WindowBlockPanel>()

    init {
        isOpaque = false
        add(createSeparatedBlock())
        add(titleLabel)
        add(errorLabel)
        add(creditsLabel)
        errorLabel.isVisible = false
        creditsLabel.isVisible = false
    }

    override fun update(quota: ProviderQuota?, error: String?, visible: Boolean) {
        isVisible = visible
        if (!visible) return
        titleLabel.text = sectionTitle("Antigravity")
        val warning =
            error
                ?: (quota as? AntigravityQuota)?.warnings?.joinToString(" ")?.takeIf {
                    it.isNotEmpty()
                }
        errorLabel.text = warning.orEmpty()
        errorLabel.isVisible = warning != null
        blocks.forEach { it.clear() }
        val credits = (quota as? AntigravityQuota)?.remainingCredits.takeIf { error == null }
        creditsLabel.isVisible = credits != null
        if (credits != null) {
            creditsLabel.text =
                message(
                    "quota.credit.balance",
                    java.text.NumberFormat.getIntegerInstance().format(credits),
                )
        }
        if (error == null && quota is AntigravityQuota) {
            for ((index, window) in quota.windows.withIndex()) {
                val label = if (window.window == "weekly") "Weekly" else window.label
                val title = "${window.group} / $label"
                val percent = window.usagePercent?.roundToInt()
                val info = buildList {
                    add(
                        when {
                            window.disabled -> "Unavailable"
                            percent == null -> "Usage unavailable"
                            else -> "$percent% used"
                        }
                    )
                    QuotaUiUtil.formatReset(window.resetsAt)?.let(::add)
                }
                    .joinToString(" - ")
                block(index).apply {
                    if (percent != null) update(title, info, percent)
                    else showUnavailable(title, info)
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
            // Windows stay above the credit balance, which closes the section.
            add(block, getComponentZOrder(creditsLabel))
        }
        return blocks[index]
    }
}
