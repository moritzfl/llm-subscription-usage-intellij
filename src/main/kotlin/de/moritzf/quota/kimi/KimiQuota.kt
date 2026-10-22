package de.moritzf.quota.kimi

import de.moritzf.quota.shared.ProviderQuota
import java.time.Duration
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class KimiQuota(
    val plan: String = "",
    val sessionUsage: KimiUsageWindow? = null,
    val totalUsage: KimiUsageWindow? = null,
    val weeklyUsage: KimiUsageWindow? = null,
    val monthlyUsage: KimiUsageWindow? = null,
    /** The coding share of [monthlyUsage]; a breakdown, not a separate limit. */
    val monthlyCodeUsage: KimiUsageWindow? = null,
    /** Paid usage beyond the plan from the booster wallet. */
    val extraUsage: KimiExtraUsage? = null,
    override var fetchedAt: Instant? = null,
    @Transient override var rawJson: String? = null,
) : ProviderQuota {
    fun windows(): List<KimiUsageWindow> =
        listOfNotNull(sessionUsage, weeklyUsage, monthlyUsage, totalUsage)

    fun primaryWindow(): KimiUsageWindow? {
        val windows = windows()
        return windows
            .filter { it.usagePercent >= 100.0 }
            .maxByOrNull { it.resetsAt?.toEpochMilliseconds() ?: Long.MIN_VALUE }
            ?: windows.firstOrNull()
    }

    override fun hasUsageState(): Boolean = windows().isNotEmpty()

    override fun usageFraction(): Double? {
        return windows().maxOfOrNull { it.usagePercent }?.let { it / 100.0 }
    }

    override fun activityWindows(): Map<String, Double> = buildMap {
        sessionUsage?.usagePercent?.let { put("session", it / 100.0) }
        totalUsage?.usagePercent?.let { put("total", it / 100.0) }
        weeklyUsage?.usagePercent?.let { put("weekly", it / 100.0) }
        monthlyUsage?.usagePercent?.let { put("monthly", it / 100.0) }
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
    @Transient val periodDuration: Duration? = periodDurationMs?.let(Duration::ofMillis)
}

/** Booster wallet amounts in cents of [currency]. */
@Serializable
data class KimiExtraUsage(
    val balanceCents: Long = 0,
    val usedCents: Long = 0,
    val limitCents: Long? = null,
    val limitEnabled: Boolean = false,
    val currency: String = "USD",
) {
    /** Used share of the monthly cap; null without an enabled cap. */
    val usagePercent: Double?
        get() =
            limitCents
                ?.takeIf { limitEnabled && it > 0 }
                ?.let { (usedCents.toDouble() / it * 100.0).coerceIn(0.0, 100.0) }
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
