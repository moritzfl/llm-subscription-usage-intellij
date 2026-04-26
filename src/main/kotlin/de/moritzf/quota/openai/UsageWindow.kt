package de.moritzf.quota.openai

import kotlinx.datetime.Instant
import java.time.Duration

/**
 * Represents usage information for one quota window.
 */
class UsageWindow(
    var usedPercent: Double = 0.0,
    var windowDuration: Duration? = null,
    var resetsAt: Instant? = null,
) {
    override fun toString(): String {
        return "UsageWindow(usedPercent=$usedPercent, windowDuration=$windowDuration, resetsAt=$resetsAt)"
    }
}
