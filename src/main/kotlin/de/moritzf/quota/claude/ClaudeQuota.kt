package de.moritzf.quota.claude

import de.moritzf.quota.shared.ProviderQuota
import java.time.Duration
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class ClaudeQuota(
    val plan: String = "",
    val fiveHourUsage: ClaudeUsageWindow? = null,
    val sevenDayUsage: ClaudeUsageWindow? = null,
    val sevenDaySonnetUsage: ClaudeUsageWindow? = null,
    val sevenDayOpusUsage: ClaudeUsageWindow? = null,
    val sevenDayOauthAppsUsage: ClaudeUsageWindow? = null,
    val routinesUsage: ClaudeUsageWindow? = null,
    /**
     * Model/surface-scoped limits from the `limits` array (for example a weekly cap for one model).
     */
    val scopedLimits: List<ClaudeUsageWindow> = emptyList(),
    val extraUsage: ClaudeExtraUsage? = null,
    /**
     * Credits outside the plan limits, such as a one-time Claude Code and Cowork credit. Using them
     * up does not stop Claude, so they do not count toward the usage shown in the status bar.
     */
    val credits: List<ClaudeCredit> = emptyList(),
    /** Prepaid usage-credit balance from the `spend` block, when the account has one. */
    val creditBalance: ClaudeMoney? = null,
    override var fetchedAt: Instant? = null,
    @Transient override var rawJson: String? = null,
    /** Read separately from the usage windows and never cached, so no stale offer is shown. */
    @Transient val resets: ClaudeResets? = null,
) : ProviderQuota {
    /** At least one usage window is used up. */
    fun atLimit(): Boolean =
        (listOfNotNull(
                fiveHourUsage,
                sevenDayUsage,
                sevenDaySonnetUsage,
                sevenDayOpusUsage,
                routinesUsage,
            ) + scopedLimits)
            .any { it.usagePercent >= 100.0 }

    override fun hasUsageState(): Boolean {
        return fiveHourUsage != null ||
            sevenDayUsage != null ||
            sevenDaySonnetUsage != null ||
            sevenDayOpusUsage != null ||
            sevenDayOauthAppsUsage != null ||
            routinesUsage != null ||
            scopedLimits.isNotEmpty() ||
            credits.isNotEmpty() ||
            extraUsage?.isEnabled == true
    }

    override fun usageFraction(): Double? {
        val windows =
            listOfNotNull(
                fiveHourUsage?.usagePercent,
                sevenDayUsage?.usagePercent,
                sevenDaySonnetUsage?.usagePercent,
                sevenDayOpusUsage?.usagePercent,
                sevenDayOauthAppsUsage?.usagePercent,
                routinesUsage?.usagePercent,
                extraUsage?.usagePercent?.takeIf { extraUsage.isEnabled },
            ) + scopedLimits.map { it.usagePercent }
        return windows.maxOrNull()?.let { it / 100.0 }
    }

    override fun activityWindows(): Map<String, Double> = buildMap {
        fiveHourUsage?.usagePercent?.let { put("fiveHour", it / 100.0) }
        sevenDayUsage?.usagePercent?.let { put("sevenDay", it / 100.0) }
        sevenDaySonnetUsage?.usagePercent?.let { put("sevenDaySonnet", it / 100.0) }
        sevenDayOpusUsage?.usagePercent?.let { put("sevenDayOpus", it / 100.0) }
        sevenDayOauthAppsUsage?.usagePercent?.let { put("sevenDayOauthApps", it / 100.0) }
        routinesUsage?.usagePercent?.let { put("routines", it / 100.0) }
        extraUsage?.usagePercent?.takeIf { extraUsage.isEnabled }?.let { put("extra", it / 100.0) }
        scopedLimits.forEachIndexed { index, window ->
            put("scoped:${window.label.ifBlank { index.toString() }}", window.usagePercent / 100.0)
        }
        credits.forEachIndexed { index, credit ->
            put("credit:${credit.label.ifBlank { index.toString() }}", credit.usagePercent / 100.0)
        }
    }

    fun primaryWindow(): ClaudeUsageWindow? {
        return fiveHourUsage
            ?: sevenDayUsage
            ?: sevenDayOauthAppsUsage
            ?: sevenDaySonnetUsage
            ?: sevenDayOpusUsage
            ?: routinesUsage
    }
}

/** The login's organization and plan name, such as "Max 20x" (empty when unknown). */
data class ClaudeProfile(val organizationUuid: String, val plan: String)

@Serializable
data class ClaudeUsageWindow(
    val label: String = "",
    val usagePercent: Double = 0.0,
    val resetsAt: Instant? = null,
    val periodDurationMs: Long? = null,
) {
    @Transient val periodDuration: Duration? = periodDurationMs?.let(Duration::ofMillis)
}

/**
 * A credit pool. [endsAt] is when it expires, or when it refills if [renews]. Dollar amounts are
 * present only when the server reports them.
 */
@Serializable
data class ClaudeCredit(
    val label: String,
    val usagePercent: Double,
    val usedDollars: Double? = null,
    val limitDollars: Double? = null,
    val endsAt: Instant? = null,
    val renews: Boolean = false,
)

/** An amount in minor units, such as cents with [exponent] 2. */
@Serializable
data class ClaudeMoney(val amountMinor: Long, val currency: String, val exponent: Int = 2) {
    @Transient val major: Double = amountMinor / Math.pow(10.0, exponent.toDouble())
}

@Serializable
data class ClaudeExtraUsage(
    val isEnabled: Boolean = false,
    val monthlyLimitCredits: Long? = null,
    val usedCredits: Long? = null,
    val usagePercent: Double? = null,
    val currency: String? = null,
    /** Enabled without a monthly cap. */
    val unlimited: Boolean = false,
    /** Why usage credits cannot be used, such as `out_of_credits` or `org_spend_cap_reached`. */
    val disabledReason: String? = null,
) {
    @Transient val monthlyLimitMajor: Double? = monthlyLimitCredits?.let { it / 100.0 }

    @Transient val usedMajor: Double? = usedCredits?.let { it / 100.0 }
}
