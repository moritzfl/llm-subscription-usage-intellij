package de.moritzf.quota.zai

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Represents Z.ai GLM Coding subscription usage quota data.
 */
@Serializable
data class ZaiQuota(
    val plan: String = "",
    val sessionUsage: ZaiUsageWindow? = null,
    val weeklyUsage: ZaiUsageWindow? = null,
    val webSearchUsage: ZaiCountUsageWindow? = null,
    var fetchedAt: Instant? = null,
    @Transient var rawJson: String? = null,
) {
    fun hasUsageState(): Boolean {
        return sessionUsage != null || weeklyUsage != null || webSearchUsage != null
    }
}

@Serializable
data class ZaiUsageWindow(
    val usagePercent: Double = 0.0,
    val resetsAt: Instant? = null,
)

@Serializable
data class ZaiCountUsageWindow(
    val used: Long = 0,
    val limit: Long = 0,
    val usagePercent: Double = 0.0,
    val resetsAt: Instant? = null,
)
