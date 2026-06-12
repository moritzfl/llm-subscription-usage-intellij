package de.moritzf.quota.openai

import de.moritzf.quota.shared.ProviderQuota
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Aggregates parsed Codex usage quota data returned by the backend.
 */
@Serializable(with = OpenAiCodexQuotaSerializer::class)
class OpenAiCodexQuota(
    var primary: UsageWindow? = null,
    var secondary: UsageWindow? = null,
    var reviewPrimary: UsageWindow? = null,
    var reviewSecondary: UsageWindow? = null,
    var planType: String? = null,
    var allowed: Boolean? = null,
    var limitReached: Boolean? = null,
    var reviewAllowed: Boolean? = null,
    var reviewLimitReached: Boolean? = null,
    override var fetchedAt: Instant? = null,
    override var rawJson: String? = null,
    var accountId: String? = null,
    var email: String? = null,
    var credits: OpenAiCredits? = null,
    var spendControl: OpenAiSpendControl? = null,
    var rateLimitReachedType: String? = null,
) : ProviderQuota {
    fun hasUsableWindows(): Boolean {
        return primary != null || secondary != null || reviewPrimary != null || reviewSecondary != null
    }

    override fun usageFraction(): Double? {
        val windows = listOfNotNull(
            primary?.usedPercent, secondary?.usedPercent,
            reviewPrimary?.usedPercent, reviewSecondary?.usedPercent,
        )
        return windows.maxOrNull()?.let { it / 100.0 }
    }

    override fun hasUsageState(): Boolean {
        return hasUsableWindows() ||
            allowed != null ||
            limitReached != null ||
            reviewAllowed != null ||
            reviewLimitReached != null ||
            credits?.hasCredits != null ||
            credits?.unlimited == true ||
            spendControl?.reached != null ||
            rateLimitReachedType != null
    }

    override fun toString(): String {
        return "OpenAiCodexQuota(" +
            "primary=$primary, " +
            "secondary=$secondary, " +
            "reviewPrimary=$reviewPrimary, " +
            "reviewSecondary=$reviewSecondary, " +
            "planType=$planType, " +
            "allowed=$allowed, " +
            "limitReached=$limitReached, " +
            "reviewAllowed=$reviewAllowed, " +
            "reviewLimitReached=$reviewLimitReached, " +
            "fetchedAt=$fetchedAt, " +
            "rawJson=${if (rawJson == null) "null" else "<redacted>"}, " +
            "accountId=${if (accountId == null) "null" else "<redacted>"}, " +
            "email=${if (email == null) "null" else "<redacted>"}, " +
            "credits=$credits, " +
            "spendControl=$spendControl, " +
            "rateLimitReachedType=$rateLimitReachedType" +
            ")"
    }
}
