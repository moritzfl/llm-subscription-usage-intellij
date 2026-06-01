package de.moritzf.quota.openai.dto

import de.moritzf.quota.openai.OpenAiCredits
import de.moritzf.quota.openai.OpenAiSpendControl
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CreditsDto(
    @SerialName("has_credits") val hasCredits: Boolean? = null,
    val unlimited: Boolean? = null,
    @SerialName("overage_limit_reached") val overageLimitReached: Boolean? = null,
    val balance: String? = null,
    @SerialName("approx_local_messages") val approxLocalMessages: List<Int>? = null,
    @SerialName("approx_cloud_messages") val approxCloudMessages: List<Int>? = null,
) {
    fun toCredits(): OpenAiCredits {
        return OpenAiCredits(
            hasCredits = hasCredits,
            unlimited = unlimited,
            overageLimitReached = overageLimitReached,
            balance = balance?.takeUnless { it.isEmpty() },
            approxLocalMessages = approxLocalMessages,
            approxCloudMessages = approxCloudMessages,
        )
    }
}

@Serializable
data class SpendControlDto(
    val reached: Boolean? = null,
    @SerialName("individual_limit") val individualLimit: Double? = null,
) {
    fun toSpendControl(): OpenAiSpendControl {
        return OpenAiSpendControl(
            reached = reached,
            individualLimit = individualLimit,
        )
    }
}

@Serializable
data class RateLimitReachedTypeDto(
    val type: String? = null,
    val details: String? = null,
)
