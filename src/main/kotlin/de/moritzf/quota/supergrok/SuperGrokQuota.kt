package de.moritzf.quota.supergrok

import de.moritzf.quota.shared.ProviderQuota
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.time.Duration

@Serializable
data class SuperGrokQuota(
    val plan: String = "",
    val authSource: String = "",
    val creditUsage: SuperGrokUsageWindow? = null,
    val onDemandCap: Long? = null,
    override var fetchedAt: Instant? = null,
    @Transient override var rawJson: String? = null,
) : ProviderQuota {
    override fun hasUsageState(): Boolean = creditUsage != null

    override fun usageFraction(): Double? = creditUsage?.usagePercent?.let { it / 100.0 }
}

@Serializable
data class SuperGrokUsageWindow(
    val label: String = "Credits used",
    val used: Long = 0,
    val limit: Long = 0,
    val usagePercent: Double = 0.0,
    val resetsAt: Instant? = null,
    val periodDurationMs: Long? = null,
) {
    @Transient
    val periodDuration: Duration? = periodDurationMs?.let(Duration::ofMillis)
}
