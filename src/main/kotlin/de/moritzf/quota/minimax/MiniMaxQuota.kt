package de.moritzf.quota.minimax

import de.moritzf.quota.shared.ProviderQuota
import java.time.Duration
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class MiniMaxQuota(
    val plan: String = "",
    val sessionUsage: MiniMaxUsageWindow? = null,
    val weeklyUsage: MiniMaxUsageWindow? = null,
    /** The weekly window has no cap. */
    val weeklyUnlimited: Boolean = false,
    /** Weekly allowance relative to the plan's base tier, such as 1.5 for a 150% boost. */
    val weeklyBoost: Double? = null,
    /** The video lane: a short window (daily or longer) and its weekly window. */
    val videoUsage: MiniMaxUsageWindow? = null,
    val videoWeeklyUsage: MiniMaxUsageWindow? = null,
    val region: MiniMaxRegion = MiniMaxRegion.GLOBAL,
    /**
     * False when MiniMax accepts the key but the account has no active M Plan subscription, so
     * requests draw on purchased Credits. MiniMax does not report the Credits balance to keys.
     */
    val planActive: Boolean = true,
    /** Credits left, as read through a MiniMax Code login. */
    val credits: MiniMaxCredits? = null,
    /** Sources that failed while another one answered. */
    val warnings: List<String> = emptyList(),
    override var fetchedAt: Instant? = null,
    @Transient override var rawJson: String? = null,
) : ProviderQuota {
    override fun hasUsageState(): Boolean =
        !planActive ||
            credits != null ||
            sessionUsage != null ||
            weeklyUsage != null ||
            weeklyUnlimited ||
            videoUsage != null

    override fun usageFraction(): Double? {
        val windows = listOfNotNull(sessionUsage?.usagePercent, weeklyUsage?.usagePercent)
        return windows.maxOrNull()?.let { it / 100.0 }
    }

    override fun activityWindows(): Map<String, Double> = buildMap {
        sessionUsage?.usagePercent?.let { put("session", it / 100.0) }
        weeklyUsage?.usagePercent?.let { put("weekly", it / 100.0) }
        videoUsage?.usagePercent?.let { put("video", it / 100.0) }
        videoWeeklyUsage?.usagePercent?.let { put("videoWeekly", it / 100.0) }
    }
}

/** MiniMax Credits: purchased packs plus free ones such as rewards. */
@Serializable
data class MiniMaxCredits(
    val remaining: Double,
    val purchased: Double? = null,
    val free: Double? = null,
)

@Serializable
data class MiniMaxUsageWindow(
    val used: Long = 0,
    val limit: Long = 0,
    val usagePercent: Double = 0.0,
    val resetsAt: Instant? = null,
    val periodDurationMs: Long? = null,
) {
    @Transient val periodDuration: Duration? = periodDurationMs?.let(Duration::ofMillis)
}

@Serializable
enum class MiniMaxRegion(private val label: String) {
    GLOBAL("GLOBAL"),
    CN("CN");

    override fun toString(): String = label
}

enum class MiniMaxRegionPreference(private val label: String) {
    AUTO("Auto"),
    GLOBAL("Global"),
    CN("CN");

    override fun toString(): String = label

    companion object {
        fun fromStorageValue(value: String?): MiniMaxRegionPreference {
            if (value.isNullOrBlank()) return AUTO
            return entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } ?: AUTO
        }
    }
}
