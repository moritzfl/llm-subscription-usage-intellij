package de.moritzf.quota.idea.ui.indicator

import de.moritzf.quota.antigravity.AntigravityQuota
import de.moritzf.quota.antigravity.AntigravityUsageWindow
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.ui.popup.AntigravityPopupSection
import de.moritzf.quota.shared.ProviderQuota
import kotlin.math.roundToInt

internal object AntigravityUi : ProviderUi {
    override val type = QuotaProviderType.ANTIGRAVITY
    override val icon get() = QuotaIcons.ANTIGRAVITY

    override fun barText(quota: ProviderQuota?, error: String?): String {
        if (error != null) return "error"
        if (quota == null) return "loading..."
        val window = (quota as? AntigravityQuota)?.primaryWindow() ?: return "no data"
        return formatPercentWithOptionalTime(window.usagePercent!!.roundToInt(), window.resetsAt, window.period())
    }

    override fun displayPercent(quota: ProviderQuota?, error: String?): Int =
        if (error != null) -1 else (quota as? AntigravityQuota)?.primaryWindow()?.usagePercent?.roundToInt() ?: -1

    override fun periodElapsedFraction(quota: ProviderQuota?, error: String?): Double? {
        if (error != null) return null
        val window = (quota as? AntigravityQuota)?.primaryWindow() ?: return null
        val reset = window.resetsAt ?: return null
        val period = window.period() ?: return null
        return computePeriodElapsedFraction(period.toMillis(), reset)
    }

    override fun authState(accountId: String): ProviderAuthState =
        if (QuotaUsageService.getInstance().getLastQuota(accountId) != null) ProviderAuthState.AUTHENTICATED
        else ProviderAuthState.UNKNOWN

    override fun createPopupSection() = AntigravityPopupSection()
}

internal fun AntigravityUsageWindow.period(): java.time.Duration? = when (window) {
    "5h" -> QuotaPeriodDurations.ROLLING_5H
    "weekly" -> QuotaPeriodDurations.WEEKLY
    else -> null
}
