package de.moritzf.quota.openai

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RateLimitResetCreditsResponse(
    @SerialName("available_count") val availableCount: Int = 0,
    val credits: List<RateLimitResetCredit> = emptyList(),
) {
    fun effectiveAvailableCount(): Int = maxOf(availableCount, credits.size)
}

@Serializable
data class RateLimitResetCredits(
    @SerialName("available_count") val availableCount: Int = 0,
    val credits: List<RateLimitResetCredit> = emptyList(),
) {
    fun effectiveAvailableCount(): Int = maxOf(availableCount, credits.size)
}

@Serializable
data class RateLimitResetCredit(
    @SerialName("credit_id") val creditId: String,
    @SerialName("expires_at") val expiresAt: Instant? = null,
)

@Serializable
data class ConsumeRateLimitResetCreditRequest(
    @SerialName("credit_id") val creditId: String? = null,
    @SerialName("redeem_request_id") val redeemRequestId: String,
)

@Serializable
data class ConsumeRateLimitResetCreditResponse(
    val code: String? = null,
    @SerialName("windows_reset") val windowsReset: Int = 0,
)
