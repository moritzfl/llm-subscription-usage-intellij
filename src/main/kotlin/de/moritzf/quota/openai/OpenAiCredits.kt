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
    if (isAssignedCreditsQuota() && spendControl?.reached == true && (spendControl?.individualLimit ?: 0.0) > 0.0) {
        return "Individual spend limit reached"
    }
    if (isCreditsDepleted()) {
        return "Credits depleted"
    }
    return null
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
