package de.moritzf.quota.supergrok

import de.moritzf.quota.shared.ProviderQuota
import java.time.Duration
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class SuperGrokQuota(
    val plan: String = "",
    val authSource: String = "",
    val creditUsage: SuperGrokUsageWindow? = null,
    /** Pay-as-you-go cap and spend in USD cents. */
    val onDemandCap: Long? = null,
    val onDemandUsed: Long? = null,
    /** Bought credits in USD cents, used once the included credits run out. */
    val prepaidBalance: Long? = null,
    /** Share of the included credits per product, such as Grok Chat or Grok Build. */
    val productUsage: List<SuperGrokProductUsage> = emptyList(),
    val autoTopUp: SuperGrokAutoTopUp? = null,
    val isUnifiedBilling: Boolean = false,
    val periodType: String = "",
    val resetTokens: List<SuperGrokResetToken> = emptyList(),
    override var fetchedAt: Instant? = null,
    @Transient override var rawJson: String? = null,
) : ProviderQuota {
    override fun hasUsageState(): Boolean = creditUsage != null

    override fun usageFraction(): Double? = creditUsage?.usagePercent?.let { it / 100.0 }
}

@Serializable data class SuperGrokProductUsage(val product: String, val usagePercent: Double)

/** Automatic credit purchase: [amountCents] whenever the balance drops below [thresholdCents]. */
@Serializable
data class SuperGrokAutoTopUp(
    val amountCents: Long,
    val thresholdCents: Long? = null,
    val monthlyMaxCents: Long? = null,
)

@Serializable
data class SuperGrokUsageWindow(
    val label: String = "",
    val used: Long = 0,
    val limit: Long = 0,
    val usagePercent: Double = 0.0,
    val resetsAt: Instant? = null,
    val periodDurationMs: Long? = null,
    /** False when percent was inferred as 0% from a period-only billing payload. */
    val reported: Boolean = true,
) {
    @Transient val periodDuration: Duration? = periodDurationMs?.let(Duration::ofMillis)

    /**
     * Unified weekly billing only sets percent (used/limit stay 0); do not treat 0/0 as exhausted.
     */
    fun isExhausted(): Boolean {
        if (usagePercent >= 100.0) return true
        return limit > 0 && used >= limit
    }
}
