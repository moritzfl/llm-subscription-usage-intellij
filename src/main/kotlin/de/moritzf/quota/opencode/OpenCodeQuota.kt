package de.moritzf.quota.opencode

import de.moritzf.quota.shared.LenientDoubleSerializer
import de.moritzf.quota.shared.ProviderQuota
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** Represents OpenCode quota data with Go usage windows and optional Zen credit balance. */
@Serializable
data class OpenCodeQuota(
    val rollingUsage: OpenCodeUsageWindow? = null,
    val weeklyUsage: OpenCodeUsageWindow? = null,
    val monthlyUsage: OpenCodeUsageWindow? = null,
    val mine: Boolean = false,
    val useBalance: Boolean = false,
    /** Go subscription product from the Console, such as `go`. */
    val product: String? = null,
    /** When a canceled Go subscription stops; null while it renews. */
    val endsAt: Instant? = null,
    /** The Go subscription has no access, for example after an unpaid renewal. */
    val paused: Boolean = false,
    /** A paused renewal is still being paid. */
    val renewalPending: Boolean = false,
    /** The last renewal payment failed or needs authorization. */
    val paymentFailed: Boolean = false,
    var availableBalance: Long? = null,
    /**
     * Credit that requests can spend, in micro cents; Go bills it past a limit with [useBalance].
     */
    var availableCredit: Long? = null,
    override var fetchedAt: Instant? = null,
    @Transient override var rawJson: String? = null,
    @Transient var rawGoJson: String? = null,
    @Transient var rawBillingJson: String? = null,
    var warnings: List<String> = emptyList(),
) : ProviderQuota {
    override fun hasUsageState(): Boolean {
        return rollingUsage != null || weeklyUsage != null || monthlyUsage != null
    }

    override fun usageFraction(): Double? {
        val windows =
            listOfNotNull(
                rollingUsage?.usagePercent,
                weeklyUsage?.usagePercent,
                monthlyUsage?.usagePercent,
            )
        return windows.maxOrNull()?.let { it / 100.0 }
    }

    override fun activityWindows(): Map<String, Double> = buildMap {
        rollingUsage?.let { put("rolling", it.usagePercent / 100.0) }
        weeklyUsage?.let { put("weekly", it.usagePercent / 100.0) }
        monthlyUsage?.let { put("monthly", it.usagePercent / 100.0) }
        // Zen credit burn: lower balance => higher activity signal.
        availableBalance?.let { put("balanceSpend", -it.toDouble() / 1_000_000.0) }
    }

    fun hasAvailableBalance(): Boolean {
        return availableBalance != null
    }

    /** A reached Go limit bills the balance instead of stopping requests. */
    fun billsBalanceAtLimit(): Boolean = useBalance && (availableCredit ?: 0) > 0

    /** "Go" or "Go Plus"; null without a Go subscription product. */
    val plan: String?
        get() =
            when {
                product.isNullOrBlank() -> null
                product.contains("plus", ignoreCase = true) -> "Go Plus"
                else -> "Go"
            }
}

/** Represents a single usage window from the OpenCode Go subscription. */
@Serializable
data class OpenCodeUsageWindow(
    val status: String = "ok",
    val resetInSec: Long = 0,
    @Serializable(with = LenientDoubleSerializer::class) val usagePercent: Double = 0.0,
) {
    val isRateLimited: Boolean
        get() = status == "rate-limited"
}
