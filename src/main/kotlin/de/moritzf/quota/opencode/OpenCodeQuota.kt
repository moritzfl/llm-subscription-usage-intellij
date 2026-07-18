package de.moritzf.quota.opencode

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
) : ProviderQuota {
    override fun hasUsageState(): Boolean {
        return rollingUsage != null || weeklyUsage != null || monthlyUsage != null
    }

    override fun usageFraction(): Double? {
        val windows = listOfNotNull(
            rollingUsage?.usagePercent?.toDouble(),
            weeklyUsage?.usagePercent?.toDouble(),
            monthlyUsage?.usagePercent?.toDouble(),
        )
        return windows.maxOrNull()?.let { it / 100.0 }
    }

    override fun activityFraction(): Double? {
        val windows = listOfNotNull(
            rollingUsage?.usagePercent?.toDouble(),
            weeklyUsage?.usagePercent?.toDouble(),
            monthlyUsage?.usagePercent?.toDouble(),
        )
        return windows.takeIf { it.isNotEmpty() }?.sum()?.let { it / 100.0 }
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
