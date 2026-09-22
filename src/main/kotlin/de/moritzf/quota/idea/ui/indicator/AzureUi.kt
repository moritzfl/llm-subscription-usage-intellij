package de.moritzf.quota.idea.ui.indicator

import de.moritzf.quota.azure.AzureQuota
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.ui.popup.AzurePopupSection
import de.moritzf.quota.shared.ProviderQuota
import kotlin.math.roundToInt

internal object AzureUi : ProviderUi {
    override val type = QuotaProviderType.AZURE
    override val icon get() = QuotaIcons.AZURE

    override fun barText(quota: ProviderQuota?, error: String?): String {
        if (error != null) return "error"
        if (quota == null) return "loading..."
        val azure = quota as? AzureQuota ?: return "no data"
        val window = azure.primaryWindow()
        if (window?.usagePercent != null) {
            return formatPercentWithOptionalTime(window.usagePercent!!.roundToInt(), window.resetsAt, null)
        }
        return azure.account?.userName?.substringBefore('@')?.takeIf { it.isNotBlank() } ?: "signed in"
    }

    override fun displayPercent(quota: ProviderQuota?, error: String?): Int =
        if (error != null) -1 else (quota as? AzureQuota)?.primaryWindow()?.usagePercent?.roundToInt() ?: -1

    override fun periodElapsedFraction(quota: ProviderQuota?, error: String?): Double? = null

    override fun authState(accountId: String): ProviderAuthState =
        if (QuotaUsageService.getInstance().getLastQuota(accountId) != null) ProviderAuthState.AUTHENTICATED
        else ProviderAuthState.UNKNOWN

    override fun createPopupSection() = AzurePopupSection()
}
