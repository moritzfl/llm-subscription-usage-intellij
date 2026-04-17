package de.moritzf.quota

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
    var fetchedAt: Instant? = null,
    var rawJson: String? = null,
    var accountId: String? = null,
    var email: String? = null,
) {
    fun hasUsableWindows(): Boolean {
        return primary != null || secondary != null || reviewPrimary != null || reviewSecondary != null
    }

    fun hasUsageState(): Boolean {
        return hasUsableWindows() ||
            allowed != null ||
            limitReached != null ||
            reviewAllowed != null ||
            reviewLimitReached != null
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
            "email=${if (email == null) "null" else "<redacted>"}" +
            ")"
    }
}
