package de.moritzf.quota.minimax

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class MiniMaxQuota(
    val plan: String = "",
    val sessionUsage: MiniMaxUsageWindow? = null,
    val region: MiniMaxRegion = MiniMaxRegion.GLOBAL,
    var fetchedAt: Instant? = null,
    @Transient var rawJson: String? = null,
) {
    fun hasUsageState(): Boolean = sessionUsage != null
}

@Serializable
data class MiniMaxUsageWindow(
    val used: Long = 0,
    val limit: Long = 0,
    val usagePercent: Double = 0.0,
    val resetsAt: Instant? = null,
    val periodDurationMs: Long? = null,
)

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
