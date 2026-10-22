package de.moritzf.quota.github

import de.moritzf.quota.shared.ProviderQuota
import java.time.Duration
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * GitHub Copilot subscription quota as reported by `copilot_internal/user`.
 *
 * Paid plans report AI credits or legacy premium requests and chat; the free tier reports absolute
 * chat/completions counters. Windows that the API marks as unlimited are kept for snapshots/API
 * output, but display helpers hide them.
 */
@Serializable
data class GitHubQuota(
    val plan: String = "",
    val subscriptionState: GitHubSubscriptionState = GitHubSubscriptionState.ACTIVE,
    val premiumInteractions: GitHubUsageWindow? = null,
    val chat: GitHubUsageWindow? = null,
    val completions: GitHubUsageWindow? = null,
    override var fetchedAt: Instant? = null,
    @Transient override var rawJson: String? = null,
) : ProviderQuota {
    override fun hasUsageState(): Boolean =
        subscriptionState != GitHubSubscriptionState.ACTIVE ||
            premiumInteractions != null ||
            chat != null ||
            completions != null

    override fun usageFraction(): Double? {
        return limitedWindows().maxOfOrNull { it.usagePercent }?.let { it / 100.0 }
    }

    override fun activityWindows(): Map<String, Double> = buildMap {
        premiumInteractions
            ?.takeUnless { it.unlimited }
            ?.usagePercent
            ?.let { put("premium", it / 100.0) }
        chat?.takeUnless { it.unlimited }?.usagePercent?.let { put("chat", it / 100.0) }
        completions
            ?.takeUnless { it.unlimited }
            ?.usagePercent
            ?.let { put("completions", it / 100.0) }
    }

    /** Business and Enterprise plans, whose organization manages the budget. */
    fun isManagedPlan(): Boolean =
        plan.equals("Copilot Business", ignoreCase = true) ||
            plan.equals("Copilot Enterprise", ignoreCase = true)

    /** Windows in display priority order, limited ones first. */
    fun limitedWindows(): List<GitHubUsageWindow> =
        listOfNotNull(premiumInteractions, chat, completions).filterNot { it.unlimited }
}

@Serializable
enum class GitHubSubscriptionState {
    ACTIVE,
    SUBSCRIPTION_ENDED,
    NO_ACTIVE_SUBSCRIPTION,
}

@Serializable
data class GitHubUsageWindow(
    val label: String = "",
    val used: Double = 0.0,
    val limit: Double = 0.0,
    val usagePercent: Double = 0.0,
    val unlimited: Boolean = false,
    val resetsAt: Instant? = null,
    val periodDurationMs: Long? = null,
    /** Usage beyond the included amount is allowed and billed to the additional budget. */
    val overagePermitted: Boolean = false,
    /** Usage beyond the included amount this period, in the same unit as [limit]. */
    val overageUsed: Double? = null,
    /** The additional budget; null or 0 when the server sets no cap. */
    val overageLimit: Double? = null,
) {
    @Transient val periodDuration: Duration? = periodDurationMs?.let(Duration::ofMillis)

    /** Requests keep working past the included amount while the additional budget lasts. */
    fun overageAvailable(): Boolean {
        if (!overagePermitted) return false
        val cap = overageLimit?.takeIf { it > 0.0 } ?: return true
        return (overageUsed ?: 0.0) < cap
    }
}

@Serializable
data class GitHubCredentials(
    val accessToken: String = "",
    val oauthClientId: String = "",
) {
    fun isUsable(): Boolean = accessToken.isNotBlank()
}
