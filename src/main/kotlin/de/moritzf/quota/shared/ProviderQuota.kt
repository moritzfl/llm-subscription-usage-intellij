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
}
