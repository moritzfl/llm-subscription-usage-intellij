package de.moritzf.quota.zai

import de.moritzf.quota.shared.ProviderQuota
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.time.Duration

/**
 * Represents Z.ai GLM Coding subscription usage quota data.
 */
@Serializable
data class ZaiQuota(
    val plan: String = "",
    val sessionUsage: ZaiUsageWindow? = null,
    val weeklyUsage: ZaiUsageWindow? = null,
    val webSearchUsage: ZaiCountUsageWindow? = null,
    override var fetchedAt: Instant? = null,
    @Transient override var rawJson: String? = null,
) : ProviderQuota {
    override fun hasUsageState(): Boolean {
        return sessionUsage != null || weeklyUsage != null || webSearchUsage != null
    }

    override fun usageFraction(): Double? {
        val windows = listOfNotNull(
            sessionUsage?.usagePercent,
            weeklyUsage?.usagePercent,
            webSearchUsage?.usagePercent,
        )
        return windows.maxOrNull()?.let { it / 100.0 }
    }

    override fun activityFraction(): Double? {
        val windows = listOfNotNull(
            sessionUsage?.usagePercent,
            weeklyUsage?.usagePercent,
            webSearchUsage?.usagePercent,
        )
        return windows.takeIf { it.isNotEmpty() }?.sum()?.let { it / 100.0 }
    }
}

@Serializable
data class ZaiUsageWindow(
    val usagePercent: Double = 0.0,
    val resetsAt: Instant? = null,
    val periodDurationMs: Long? = null,
) {
    @Transient
    val periodDuration: Duration? = periodDurationMs?.let(Duration::ofMillis)
}

@Serializable
data class ZaiCountUsageWindow(
    val used: Long = 0,
    val limit: Long = 0,
    val usagePercent: Double = 0.0,
    val resetsAt: Instant? = null,
    val periodDurationMs: Long? = null,
) {
    @Transient
    val periodDuration: Duration? = periodDurationMs?.let(Duration::ofMillis)
}
