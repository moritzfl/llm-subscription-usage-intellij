package de.moritzf.quota.cursor

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Represents Cursor subscription usage from the Cursor dashboard API.
 */
@Serializable
data class CursorQuota(
    val planName: String = "",
    val email: String = "",
    val membershipType: String = "",
    val planUsage: CursorPlanUsage? = null,
    val spendLimit: CursorSpendLimit? = null,
    val displayMessage: String = "",
    val autoModelDisplayMessage: String = "",
    val apiModelDisplayMessage: String = "",
    var fetchedAt: Instant? = null,
    @Transient var rawJson: String? = null,
) {
    fun primaryUsagePercent(): Double? {
        spendLimit?.usagePercent()?.let { return it }
        return planUsage?.totalPercentUsed
    }

    fun hasUsageState(): Boolean = planUsage != null || spendLimit != null
}

@Serializable
data class CursorPlanUsage(
    val totalPercentUsed: Double = 0.0,
    val autoPercentUsed: Double = 0.0,
    val apiPercentUsed: Double = 0.0,
    val totalSpendUsd: Double = 0.0,
    val limitUsd: Double = 0.0,
    val billingCycleEnd: Instant? = null,
)

@Serializable
data class CursorSpendLimit(
    val pooledLimitUsd: Double = 0.0,
    val pooledUsedUsd: Double = 0.0,
    val pooledRemainingUsd: Double = 0.0,
    val limitType: String = "",
) {
    fun usagePercent(): Double? {
        if (pooledLimitUsd <= 0.0) return null
        return (pooledUsedUsd / pooledLimitUsd) * 100.0
    }
}
