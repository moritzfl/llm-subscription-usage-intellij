package de.moritzf.quota.shared

import kotlin.time.Instant

/**
 * Common contract implemented by every provider quota model so the IDE plumbing
 * (refresh service, snapshot cache, indicator, popup) can treat them uniformly.
 */
interface ProviderQuota {
    var fetchedAt: Instant?
    var rawJson: String?

    /** True when the payload carries any usage information worth displaying. */
    fun hasUsageState(): Boolean

    /** Usage of the most constrained window as a 0..1 fraction, or null when unknown. */
    fun usageFraction(): Double?

    /**
     * Aggregate used to detect recent activity ("Last used" indicator source).
     * Must increase whenever usage in ANY window grows, so multi-window providers
     * should return the sum of all window fractions instead of the max
     * (a small window's growth must not be masked by a larger, slower window).
     */
    fun activityFraction(): Double? = usageFraction()
}
