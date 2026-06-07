package de.moritzf.quota.opencode

import kotlinx.datetime.Instant
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
    var fetchedAt: Instant? = null,
    @Transient var rawJson: String? = null,
    @Transient var rawGoJson: String? = null,
    @Transient var rawBillingJson: String? = null,
) {
    fun hasUsageState(): Boolean {
        return rollingUsage != null || weeklyUsage != null || monthlyUsage != null
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
    val usagePercent: Int = 0,
) {
    val isRateLimited: Boolean get() = status == "rate-limited"
}
