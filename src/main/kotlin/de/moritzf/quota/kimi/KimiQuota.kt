package de.moritzf.quota.kimi

import de.moritzf.quota.shared.ProviderQuota
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.time.Duration

@Serializable
data class KimiQuota(
    val plan: String = "",
    val sessionUsage: KimiUsageWindow? = null,
    val totalUsage: KimiUsageWindow? = null,
    override var fetchedAt: Instant? = null,
    @Transient override var rawJson: String? = null,
) : ProviderQuota {
    override fun hasUsageState(): Boolean = sessionUsage != null || totalUsage != null

    override fun usageFraction(): Double? {
        val windows = listOfNotNull(sessionUsage?.usagePercent, totalUsage?.usagePercent)
        return windows.maxOrNull()?.let { it / 100.0 }
    }
}

@Serializable
data class KimiUsageWindow(
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
data class KimiCredentials(
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAtEpochSeconds: Double? = null,
    val scope: String? = null,
    val tokenType: String = "Bearer",
) {
    fun isUsable(): Boolean = accessToken.isNotBlank() || refreshToken.isNotBlank()
}
