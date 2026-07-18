package de.moritzf.quota.cursor

import de.moritzf.quota.shared.ProviderQuota
import kotlin.time.Instant
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
    val onDemandUsage: CursorOnDemandUsage? = null,
    val teamOnDemandUsage: CursorOnDemandUsage? = null,
    val requestUsage: CursorRequestUsage? = null,
    val displayMessage: String = "",
    val autoModelDisplayMessage: String = "",
    val apiModelDisplayMessage: String = "",
    override var fetchedAt: Instant? = null,
    @Transient override var rawJson: String? = null,
) : ProviderQuota {
    fun primaryUsagePercent(): Double? {
        requestUsage?.usagePercent()?.let { return it }
        planUsage?.totalPercentUsed?.let { return it }
        spendLimit?.usagePercent()?.let { return it }
        onDemandUsage?.usagePercent()?.let { return it }
        return teamOnDemandUsage?.usagePercent()
    }

    override fun hasUsageState(): Boolean =
        planUsage != null || spendLimit != null || onDemandUsage != null || teamOnDemandUsage != null || requestUsage != null

    override fun usageFraction(): Double? = primaryUsagePercent()?.let { it / 100.0 }

    override fun activityFraction(): Double? {
        val windows = listOfNotNull(
            requestUsage?.usagePercent(),
            planUsage?.totalPercentUsed,
            spendLimit?.usagePercent(),
            onDemandUsage?.usagePercent(),
            teamOnDemandUsage?.usagePercent(),
        )
        return windows.takeIf { it.isNotEmpty() }?.sum()?.let { it / 100.0 }
    }
}

@Serializable
data class CursorPlanUsage(
    val totalPercentUsed: Double = 0.0,
    val autoPercentUsed: Double = 0.0,
    val apiPercentUsed: Double = 0.0,
    val totalSpendUsd: Double = 0.0,
    val limitUsd: Double = 0.0,
    val billingCycleStart: Instant? = null,
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

@Serializable
data class CursorOnDemandUsage(
    val usedUsd: Double = 0.0,
    val limitUsd: Double? = null,
    val remainingUsd: Double? = null,
    val scope: String = "",
    val enabled: Boolean = true,
) {
    fun usagePercent(): Double? {
        val limit = limitUsd ?: return null
        if (limit <= 0.0) return null
        return (usedUsd / limit) * 100.0
    }
}

@Serializable
data class CursorRequestUsage(
    val used: Int = 0,
    val limit: Int = 0,
) {
    fun usagePercent(): Double? {
        if (limit <= 0) return null
        return (used.toDouble() / limit.toDouble()) * 100.0
    }
}
