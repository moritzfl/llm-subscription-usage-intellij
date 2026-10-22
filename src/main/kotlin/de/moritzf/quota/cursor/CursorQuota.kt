package de.moritzf.quota.cursor

import de.moritzf.quota.shared.ProviderQuota
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** Represents Cursor subscription usage from the Cursor dashboard API. */
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
    /** The Grok Bot weekly allowance; absent on combined plans, where it is the included pool. */
    val grokUsage: CursorGrokUsage? = null,
    val displayMessage: String = "",
    val autoModelDisplayMessage: String = "",
    val apiModelDisplayMessage: String = "",
    override var fetchedAt: Instant? = null,
    @Transient override var rawJson: String? = null,
) : ProviderQuota {
    fun primaryUsagePercent(): Double? {
        planUsage?.modelPools()?.values?.maxOrNull()?.let {
            return it
        }
        requestUsage?.usagePercent()?.let {
            return it
        }
        planUsage?.usagePercent()?.let {
            return it
        }
        spendLimit?.usagePercent()?.let {
            return it
        }
        onDemandUsage?.usagePercent()?.let {
            return it
        }
        teamOnDemandUsage?.usagePercent()?.let {
            return it
        }
        return grokUsage?.usagePercent
    }

    override fun hasUsageState(): Boolean =
        planUsage != null ||
            spendLimit != null ||
            onDemandUsage != null ||
            teamOnDemandUsage != null ||
            requestUsage != null ||
            grokUsage != null

    override fun usageFraction(): Double? = primaryUsagePercent()?.let { it / 100.0 }

    override fun activityWindows(): Map<String, Double> = buildMap {
        if (planUsage?.modelPools()?.isNotEmpty() == true) {
            planUsage.autoPercentUsed?.let { put("cursorModels", it / 100.0) }
            planUsage.apiPercentUsed?.let { put("otherModels", it / 100.0) }
        } else {
            requestUsage?.usagePercent()?.let { put("request", it / 100.0) }
            planUsage?.usagePercent()?.let { put("plan", it / 100.0) }
        }
        spendLimit?.usagePercent()?.let { put("spendLimit", it / 100.0) }
        onDemandUsage?.usagePercent()?.let { put("onDemand", it / 100.0) }
        teamOnDemandUsage?.usagePercent()?.let { put("teamOnDemand", it / 100.0) }
        grokUsage?.usagePercent?.let { put("grok", it / 100.0) }
    }
}

/** The Grok Bot allowance: weekly when paid, or a trial until [trialEndsAt] without a reset. */
@Serializable
data class CursorGrokUsage(
    val usagePercent: Double,
    val resetsAt: Instant? = null,
    val trialEndsAt: Instant? = null,
)

@Serializable
data class CursorPlanUsage(
    val totalPercentUsed: Double? = null,
    val autoPercentUsed: Double? = null,
    val apiPercentUsed: Double? = null,
    val totalSpendUsd: Double = 0.0,
    val limitUsd: Double = 0.0,
    val billingCycleStart: Instant? = null,
    val billingCycleEnd: Instant? = null,
    /** Set when the included pool resets weekly rather than with the billing cycle. */
    val weeklyResetsAt: Instant? = null,
) {
    /**
     * When the included pool refills: weekly on current plans, otherwise with the billing cycle.
     */
    fun includedResetsAt(): Instant? = weeklyResetsAt ?: billingCycleEnd

    // The API retains its old auto/api field names for the two independent model pools.
    fun modelPools(): Map<String, Double> = buildMap {
        autoPercentUsed?.let { put("Cursor Models", it) }
        apiPercentUsed?.let { put("Other Models", it) }
    }

    fun usagePercent(): Double? =
        modelPools().values.maxOrNull()
            ?: totalPercentUsed
            ?: limitUsd.takeIf { it > 0.0 }?.let { totalSpendUsd / it * 100.0 }
}

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
