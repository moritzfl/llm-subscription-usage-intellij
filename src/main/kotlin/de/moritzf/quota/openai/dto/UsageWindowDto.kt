package de.moritzf.quota.openai.dto

import de.moritzf.quota.openai.UsageWindow
import de.moritzf.quota.shared.LenientDoubleOrNullSerializer
import java.time.Duration
import kotlin.math.roundToLong
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** DTO for one raw usage window entry returned by the usage endpoint. */
@Serializable
data class UsageWindowDto(
    @SerialName("used_percent")
    @Serializable(with = LenientDoubleOrNullSerializer::class)
    val usedPercent: Double? = null,
    @SerialName("limit_window_seconds")
    @Serializable(with = LenientDoubleOrNullSerializer::class)
    val limitWindowSeconds: Double? = null,
    @SerialName("reset_at")
    @Serializable(with = LenientDoubleOrNullSerializer::class)
    val resetAt: Double? = null,
) {
    fun toUsageWindow(): UsageWindow? {
        val rawUsedPercent = usedPercent ?: return null
        return UsageWindow(
            usedPercent = rawUsedPercent.clampPercent(),
            windowDuration =
                limitWindowSeconds?.let { Duration.ofMillis((it * 1000.0).roundToLong()) },
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
