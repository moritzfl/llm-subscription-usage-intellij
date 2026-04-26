package de.moritzf.quota

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Represents OpenCode Go subscription quota with rolling, weekly, and monthly usage windows.
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
    var rawJson: String? = null,
) {
    fun hasUsageState(): Boolean {
        return rollingUsage != null || weeklyUsage != null || monthlyUsage != null
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
