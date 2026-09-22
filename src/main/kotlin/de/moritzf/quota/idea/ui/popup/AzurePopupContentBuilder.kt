package de.moritzf.quota.idea.ui.popup

import de.moritzf.quota.azure.AzureQuota
import de.moritzf.quota.azure.AzureUsageWindow
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.shared.ProviderQuota
import kotlin.math.roundToInt

internal class AzurePopupSection : ProviderPopupSection() {
    private val titleLabel = createSectionTitleLabel("Azure", QuotaIcons.AZURE)
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
        titleLabel.text = sectionTitle("Azure")
        val azure = quota as? AzureQuota
        val warning = error ?: azure?.warnings?.joinToString(" ")?.takeIf { it.isNotEmpty() }
        errorLabel.text = warning.orEmpty()
        errorLabel.isVisible = warning != null
        blocks.forEach { it.clear() }
        if (error == null && azure != null) {
            var index = 0
            val identity = azure.account
            if (identity != null) {
                val who = identity.userName ?: "Signed in"
                val sub = identity.subscriptionName ?: identity.subscriptionId ?: "subscription"
                val info = buildList {
                    add(who)
                    identity.tier?.let { add(it) }
                }.joinToString(" · ")
                block(index++).showUnavailable(sub, info)
            }
            val windows = azure.currentWindows()
            for (window in windows) {
                val percent = window.usagePercent?.roundToInt()
                val info = window.describe()
                block(index++).apply {
                    if (percent != null) update(window.label, info, percent) else showUnavailable(window.label, info)
                }
            }
            if (windows.isEmpty() && azure.models.isNotEmpty()) {
                block(index).showUnavailable("Deployments", azure.models.joinToString(", "))
            }
        } else if (error == null) {
            block(0).showLoading("Quota")
        }
        revalidate()
        repaint()
    }

    private fun AzureUsageWindow.describe(): String {
        val amount = when {
            kind == AzureUsageWindow.LIVE && remaining != null && limit != null ->
                "${remaining.toLong()} remaining of ${limit.toLong()} ${unit.orEmpty()}".trim()
            kind == AzureUsageWindow.ALLOCATION && used != null && limit != null ->
                "${used.toLong()}/${limit.toLong()} allocated"
            kind == AzureUsageWindow.DEPLOYMENT && used != null ->
                "${used.toLong()} ${unit.orEmpty()} allocated".trim()
            usagePercent == null -> "Usage unavailable"
            else -> "${usagePercent!!.roundToInt()}% used"
        }
        return listOfNotNull(amount, QuotaUiUtil.formatReset(resetsAt)).joinToString(" - ")
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
