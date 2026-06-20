package de.moritzf.quota.openai.dto

import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.openai.OpenAiExtraRateLimit
import de.moritzf.quota.openai.RateLimitResetCredits
import de.moritzf.quota.openai.UsageWindow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Duration
import java.util.Locale

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
    val credits: CreditsDto? = null,
    @SerialName("spend_control") val spendControl: SpendControlDto? = null,
    @SerialName("rate_limit_reached_type") val rateLimitReachedType: RateLimitReachedTypeDto? = null,
    @SerialName("rate_limit_reset_credits") val rateLimitResetCredits: RateLimitResetCredits? = null,
    @SerialName("additional_rate_limits") val additionalRateLimits: List<AdditionalRateLimitDto>? = null,
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
            credits = credits?.toCredits(),
            spendControl = spendControl?.toSpendControl(),
            rateLimitReachedType = rateLimitReachedType?.type?.takeUnless { it.isEmpty() },
            resetCreditsAvailableCount = rateLimitResetCredits?.effectiveAvailableCount() ?: 0,
            resetCredits = rateLimitResetCredits?.credits.orEmpty(),
            extraRateLimits = additionalRateLimits.orEmpty().toExtraRateLimits(),
        )
    }

    private fun String?.normalizedText(): String? = this?.takeUnless { it.isEmpty() }

    private fun List<AdditionalRateLimitDto>.toExtraRateLimits(): List<OpenAiExtraRateLimit> {
        val usedIds = mutableSetOf<String>()
        return flatMap { it.toExtraRateLimits(usedIds) }
    }
}

@Serializable
data class AdditionalRateLimitDto(
    @SerialName("limit_name") val limitName: String? = null,
    @SerialName("metered_feature") val meteredFeature: String? = null,
    @SerialName("rate_limit") val rateLimit: RateLimitDto? = null,
) {
    fun toExtraRateLimits(usedIds: MutableSet<String>): List<OpenAiExtraRateLimit> {
        val source = firstNonEmpty(limitName, meteredFeature) ?: return emptyList()
        val baseTitle = displayTitle(source)
        val baseId = slug(baseTitle).takeIf { it.isNotEmpty() } ?: slug(source).takeIf { it.isNotEmpty() } ?: return emptyList()
        return listOfNotNull(
            rateLimit?.primaryUsageWindow()?.toExtraRateLimit(baseId, baseTitle, "primary", usedIds),
            rateLimit?.secondaryUsageWindow()?.toExtraRateLimit(baseId, baseTitle, "secondary", usedIds),
        )
    }

    private fun UsageWindow.toExtraRateLimit(
        baseId: String,
        baseTitle: String,
        fallbackWindowName: String,
        usedIds: MutableSet<String>,
    ): OpenAiExtraRateLimit? {
        val windowTitle = windowTitle(fallbackWindowName)
        val id = windowId(baseId, fallbackWindowName, windowTitle)
        if (!usedIds.add(id)) return null
        return OpenAiExtraRateLimit(id, "$baseTitle $windowTitle", this)
    }

    private fun firstNonEmpty(vararg values: String?): String? {
        return values.firstNotNullOfOrNull { value -> value?.trim()?.takeIf { it.isNotEmpty() } }
    }

    private fun slug(value: String): String {
        return value.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
    }

    private fun displayTitle(source: String): String {
        return source.trim()
            .replace(Regex("(?i)^gpt-[0-9.]+-"), "")
            .replace(Regex("[_-]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word -> titleWord(word) }
            .takeIf { it.isNotBlank() }
            ?: source
    }

    private fun titleWord(word: String): String {
        return when {
            word.equals("gpt", ignoreCase = true) -> "GPT"
            word.equals("codex", ignoreCase = true) -> "Codex"
            word.all { it.isDigit() || it == '.' } -> word
            else -> word.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }
        }
    }

    private fun UsageWindow.windowTitle(fallbackWindowName: String): String {
        val duration = windowDuration ?: return titleWord(fallbackWindowName)
        val minutes = duration.toMinutes()
        return when {
            duration.isNear(Duration.ofHours(5)) -> "5-hour"
            duration.isNear(Duration.ofDays(7)) -> "Weekly"
            duration.isNear(Duration.ofDays(30)) -> "Monthly"
            minutes >= 10080L && minutes % 10080L == 0L -> minutes.let { value ->
                val weeks = value / 10080L
                if (weeks == 1L) "Weekly" else "$weeks-week"
            }
            minutes >= 1440L && minutes % 1440L == 0L -> minutes.let { value ->
                val days = value / 1440L
                if (days == 1L) "Daily" else "$days-day"
            }
            minutes >= 60L && minutes % 60L == 0L -> minutes.let { value ->
                val hours = value / 60L
                if (hours == 1L) "Hourly" else "$hours-hour"
            }
            minutes > 0L -> if (minutes == 1L) "Minute" else "$minutes-minute"
            else -> titleWord(fallbackWindowName)
        }
    }

    private fun windowId(baseId: String, fallbackWindowName: String, windowTitle: String): String {
        return if (fallbackWindowName == "primary") {
            baseId
        } else {
            "$baseId-${slug(windowTitle).ifEmpty { fallbackWindowName }}"
        }
    }

    private fun Duration.isNear(target: Duration): Boolean {
        return kotlin.math.abs(seconds - target.seconds) <= 60L
    }
}
