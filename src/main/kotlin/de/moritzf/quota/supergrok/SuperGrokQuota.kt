package de.moritzf.quota.supergrok

import de.moritzf.quota.shared.ProviderQuota
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.time.Duration

@Serializable
data class SuperGrokQuota(
    val plan: String = "",
    val authSource: String = "",
    val creditUsage: SuperGrokUsageWindow? = null,
    val onDemandCap: Long? = null,
    val isUnifiedBilling: Boolean = false,
    val periodType: String = "",
    override var fetchedAt: Instant? = null,
    @Transient override var rawJson: String? = null,
) : ProviderQuota {
    override fun hasUsageState(): Boolean = creditUsage != null

    override fun usageFraction(): Double? = creditUsage?.usagePercent?.let { it / 100.0 }
}

@Serializable
data class SuperGrokUsageWindow(
    val label: String = "",
    val used: Long = 0,
    val limit: Long = 0,
    val usagePercent: Double = 0.0,
    val resetsAt: Instant? = null,
    val periodDurationMs: Long? = null,
) {
    @Transient
    val periodDuration: Duration? = periodDurationMs?.let(Duration::ofMillis)

    /** Unified weekly billing only sets percent (used/limit stay 0); do not treat 0/0 as exhausted. */
    fun isExhausted(): Boolean {
        if (usagePercent >= 100.0) return true
        return limit > 0 && used >= limit
    }
}