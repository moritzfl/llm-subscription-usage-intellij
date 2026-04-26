package de.moritzf.quota.openai.dto

import de.moritzf.quota.openai.OpenAiCodexQuota
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTO for the top-level usage response payload.
 */
@Serializable
data class UsageResponseDto(
    @SerialName("user_id") val userId: String? = null,
    @SerialName("account_id") val accountId: String? = null,
    val email: String? = null,
    @SerialName("rate_limit") val rateLimit: RateLimitDto? = null,
    @SerialName("code_review_rate_limit") val codeReviewRateLimit: RateLimitDto? = null,
    @SerialName("plan_type") val planType: String? = null,
) {
    fun toQuota(): OpenAiCodexQuota {
        return OpenAiCodexQuota(
            primary = rateLimit?.primaryUsageWindow(),
            secondary = rateLimit?.secondaryUsageWindow(),
            reviewPrimary = codeReviewRateLimit?.primaryUsageWindow(),
            reviewSecondary = codeReviewRateLimit?.secondaryUsageWindow(),
            planType = planType.normalizedText(),
            allowed = rateLimit?.allowed,
            limitReached = rateLimit?.limitReached,
            reviewAllowed = codeReviewRateLimit?.allowed,
            reviewLimitReached = codeReviewRateLimit?.limitReached,
            accountId = accountId.normalizedText(),
            email = email.normalizedText(),
        )
    }

    private fun String?.normalizedText(): String? = this?.takeUnless { it.isEmpty() }
}
