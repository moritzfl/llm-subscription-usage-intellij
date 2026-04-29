package de.moritzf.quota.kimi

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class KimiQuota(
    val plan: String = "",
    val sessionUsage: KimiUsageWindow? = null,
    val totalUsage: KimiUsageWindow? = null,
    var fetchedAt: Instant? = null,
    @Transient var rawJson: String? = null,
) {
    fun hasUsageState(): Boolean = sessionUsage != null || totalUsage != null
}

@Serializable
data class KimiUsageWindow(
    val used: Long = 0,
    val limit: Long = 0,
    val usagePercent: Double = 0.0,
    val resetsAt: Instant? = null,
    val periodDurationMs: Long? = null,
)

@Serializable
data class KimiCredentials(
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAtEpochSeconds: Double? = null,
    val scope: String? = null,
    val tokenType: String = "Bearer",
) {
    fun isUsable(): Boolean = accessToken.isNotBlank() || refreshToken.isNotBlank()
}
