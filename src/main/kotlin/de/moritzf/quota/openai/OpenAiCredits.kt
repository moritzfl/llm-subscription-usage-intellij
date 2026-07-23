package de.moritzf.quota.openai

import kotlinx.serialization.Serializable

@Serializable
data class OpenAiCredits(
    val hasCredits: Boolean? = null,
    val unlimited: Boolean? = null,
    val overageLimitReached: Boolean? = null,
    val balance: String? = null,
    val approxLocalMessages: List<Int>? = null,
    val approxCloudMessages: List<Int>? = null,
)

@Serializable
data class OpenAiSpendControl(
    val reached: Boolean? = null,
    val individualLimit: Double? = null,
    val used: Double? = null,
    val remaining: Double? = null,
    val usedPercent: Double? = null,
    val resetAtEpochSeconds: Long? = null,
)

fun OpenAiCodexQuota.isAssignedCreditsQuota(): Boolean {
    if (rateLimitReachedType == "workspace_member_credits_depleted") {
        return true
    }
    return !hasUsableWindows() && credits != null
}

fun OpenAiCodexQuota.isCreditsDepleted(): Boolean {
    if (!isAssignedCreditsQuota()) {
        return false
    }
    if (credits?.unlimited == true) {
        return false
    }
    if (credits?.hasCredits == false) {
        return true
    }
    if (credits?.overageLimitReached == true) {
        return true
    }
    if (spendControl?.reached == true) {
        return true
    }
    return rateLimitReachedType == "workspace_member_credits_depleted"
}

fun OpenAiCodexQuota.hasAssignedCreditsQuota(): Boolean = isAssignedCreditsQuota()

fun OpenAiCodexQuota.creditsLimitWarning(): String? {
    if (rateLimitReachedType == "workspace_member_credits_depleted") {
        return "Assigned credits depleted"
    }
    // Team/business accounts can hit a per-member spend cap while rate windows still have headroom.
    // Only treat as individual spend when the payload includes a concrete cap/object detail
    // (bare spend_control.reached is also used for other workspace limit types).
    val spend = spendControl
    if (spend?.reached == true && (
            (spend.individualLimit ?: 0.0) > 0.0 ||
                spend.usedPercent != null ||
                spend.used != null
            )
    ) {
        return "Individual spend limit reached"
    }
    if (isCreditsDepleted()) {
        return "Credits depleted"
    }
    return null
}

fun OpenAiCodexQuota.hasSpendControlDetail(): Boolean {
    val spend = spendControl ?: return false
    return spend.reached != null ||
        (spend.individualLimit != null && spend.individualLimit > 0.0) ||
        spend.usedPercent != null
}

fun formatApproxMessages(range: List<Int>?): String? {
    if (range.isNullOrEmpty()) {
        return null
    }
    if (range.size == 1) {
        return range[0].toString()
    }
    val min = range.min()
    val max = range.max()
    return if (min == max) min.toString() else "$min-$max"
}
