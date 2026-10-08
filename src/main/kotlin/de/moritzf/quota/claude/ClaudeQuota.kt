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
    override var fetchedAt: Instant? = null,
    @Transient override var rawJson: String? = null,
) : ProviderQuota {
    override fun hasUsageState(): Boolean {
        return fiveHourUsage != null ||
            sevenDayUsage != null ||
            sevenDaySonnetUsage != null ||
            sevenDayOpusUsage != null ||
            sevenDayOauthAppsUsage != null ||
            routinesUsage != null ||
            scopedLimits.isNotEmpty() ||
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

@Serializable
data class ClaudeUsageWindow(
    val label: String = "",
    val usagePercent: Double = 0.0,
    val resetsAt: Instant? = null,
    val periodDurationMs: Long? = null,
) {
    @Transient val periodDuration: Duration? = periodDurationMs?.let(Duration::ofMillis)
}

@Serializable
data class ClaudeExtraUsage(
    val isEnabled: Boolean = false,
    val monthlyLimitCredits: Long? = null,
    val usedCredits: Long? = null,
    val usagePercent: Double? = null,
    val currency: String? = null,
) {
    @Transient val monthlyLimitMajor: Double? = monthlyLimitCredits?.let { it / 100.0 }

    @Transient val usedMajor: Double? = usedCredits?.let { it / 100.0 }
}
