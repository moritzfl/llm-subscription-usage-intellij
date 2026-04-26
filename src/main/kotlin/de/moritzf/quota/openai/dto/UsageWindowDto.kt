package de.moritzf.quota.openai.dto

import de.moritzf.quota.openai.UsageWindow
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Duration
import kotlin.math.roundToLong

/**
 * DTO for one raw usage window entry returned by the usage endpoint.
 */
@Serializable
data class UsageWindowDto(
    @SerialName("used_percent") val usedPercent: Double? = null,
    @SerialName("limit_window_seconds") val limitWindowSeconds: Double? = null,
    @SerialName("reset_at") val resetAt: Double? = null,
) {
    fun toUsageWindow(): UsageWindow? {
        val rawUsedPercent = usedPercent ?: return null
        return UsageWindow(
            usedPercent = rawUsedPercent.clampPercent(),
            windowDuration = limitWindowSeconds?.let { Duration.ofMillis((it * 1000.0).roundToLong()) },
            resetsAt = resetAt?.let { Instant.fromEpochMilliseconds((it * 1000.0).roundToLong()) },
        )
    }

    private fun Double.clampPercent(): Double {
        return when {
            isNaN() || isInfinite() -> 0.0
            this < 0.0 -> 0.0
            this > 100.0 -> 100.0
            else -> this
        }
    }
}
