package de.moritzf.quota.shared

import kotlin.time.Instant

/**
 * Common contract implemented by every provider quota model so the IDE plumbing (refresh service,
 * snapshot cache, indicator, popup) can treat them uniformly.
 */
interface ProviderQuota {
    var fetchedAt: Instant?
    var rawJson: String?

    /** True when the payload carries any usage information worth displaying. */
    fun hasUsageState(): Boolean

    /** Usage of the most constrained window as a 0..1 fraction, or null when unknown. */
    fun usageFraction(): Double?

    /**
     * Named usage windows (0..1 fractions) for "Last used" detection. Each entry is one independent
     * limit. Growth in ANY named window counts as activity — do not pre-sum windows here (decay in
     * one window must not cancel growth in another).
     */
    fun activityWindows(): Map<String, Double> =
        usageFraction()?.let { mapOf("usage" to it) } ?: emptyMap()

    /** Sum of [activityWindows]; kept for tests/diagnostics, not for last-used detection. */
    fun activityFraction(): Double? = activityWindows().values.takeIf { it.isNotEmpty() }?.sum()
}
