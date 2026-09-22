package de.moritzf.quota.opencode

import de.moritzf.quota.shared.LenientDoubleSerializer
import de.moritzf.quota.shared.ProviderQuota
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Represents OpenCode quota data with Go usage windows and optional Zen credit balance.
 */
@Serializable
data class OpenCodeQuota(
    val rollingUsage: OpenCodeUsageWindow? = null,
    val weeklyUsage: OpenCodeUsageWindow? = null,
    val monthlyUsage: OpenCodeUsageWindow? = null,
    val mine: Boolean = false,
    val useBalance: Boolean = false,
    var availableBalance: Long? = null,
    override var fetchedAt: Instant? = null,
    @Transient override var rawJson: String? = null,
    @Transient var rawGoJson: String? = null,
    @Transient var rawBillingJson: String? = null,
    var warnings: List<String> = emptyList(),
) : ProviderQuota {
    override fun hasUsageState(): Boolean {
        return rollingUsage != null || weeklyUsage != null || monthlyUsage != null
    }

    override fun usageFraction(): Double? {
        val windows = listOfNotNull(
            rollingUsage?.usagePercent,
            weeklyUsage?.usagePercent,
            monthlyUsage?.usagePercent,
        )
        return windows.maxOrNull()?.let { it / 100.0 }
    }

    override fun activityWindows(): Map<String, Double> = buildMap {
        rollingUsage?.let { put("rolling", it.usagePercent / 100.0) }
        weeklyUsage?.let { put("weekly", it.usagePercent / 100.0) }
        monthlyUsage?.let { put("monthly", it.usagePercent / 100.0) }
        // Zen credit burn: lower balance => higher activity signal.
        availableBalance?.let { put("balanceSpend", -it.toDouble() / 1_000_000.0) }
    }

    fun hasAvailableBalance(): Boolean {
        return availableBalance != null
    }
}

/**
 * Represents a single usage window from the OpenCode Go subscription.
 */
@Serializable
data class OpenCodeUsageWindow(
    val status: String = "ok",
    val resetInSec: Long = 0,
    @Serializable(with = LenientDoubleSerializer::class)
    val usagePercent: Double = 0.0,
) {
    val isRateLimited: Boolean get() = status == "rate-limited"
}
