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
            azure.account?.let { identity ->
                val subscription = identity.subscriptionName ?: identity.subscriptionId ?: "Subscription unavailable"
                block(index++).showUnavailable(subscription, "User: ${identity.userName ?: "unavailable"}")
            }
            val deployments = azure.currentWindows().filter { it.kind == AzureUsageWindow.DEPLOYMENT }
            for ((resource, windows) in deployments.groupBy { it.resourceName to it.location }) {
                val (name, region) = resource
                block(index++).showUnavailable("Resource: ${name ?: "unavailable"}", "Region: ${region ?: "unavailable"}")
                for (model in windows.map { it.modelName?.takeIf(String::isNotBlank) ?: it.id }.distinct()) {
                    block(index++).showTitleOnly(model)
                }
            }
            for (window in azure.liveWindows()) {
                block(index++).update("Live rate limit: ${window.label}", window.describeLive(), window.usagePercent!!.roundToInt())
            }
        } else if (error == null) {
            block(0).showUnavailable("Azure", "Loading...")
        }
        revalidate()
        repaint()
    }

    private fun AzureUsageWindow.describeLive(): String {
        val amount = "${remaining!!.toLong()} remaining of ${limit!!.toLong()} ${unit.orEmpty()}".trim()
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
