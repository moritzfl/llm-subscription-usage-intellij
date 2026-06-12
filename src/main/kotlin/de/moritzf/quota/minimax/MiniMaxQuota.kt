package de.moritzf.quota.minimax

import de.moritzf.quota.shared.ProviderQuota
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.time.Duration

@Serializable
data class MiniMaxQuota(
    val plan: String = "",
    val sessionUsage: MiniMaxUsageWindow? = null,
    val region: MiniMaxRegion = MiniMaxRegion.GLOBAL,
    override var fetchedAt: Instant? = null,
    @Transient override var rawJson: String? = null,
) : ProviderQuota {
    override fun hasUsageState(): Boolean = sessionUsage != null

    override fun usageFraction(): Double? = sessionUsage?.usagePercent?.let { it / 100.0 }
}

@Serializable
data class MiniMaxUsageWindow(
    val used: Long = 0,
    val limit: Long = 0,
    val usagePercent: Double = 0.0,
    val resetsAt: Instant? = null,
    val periodDurationMs: Long? = null,
) {
    @Transient
    val periodDuration: Duration? = periodDurationMs?.let(Duration::ofMillis)
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
