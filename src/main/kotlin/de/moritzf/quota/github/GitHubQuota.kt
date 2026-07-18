package de.moritzf.quota.github

import de.moritzf.quota.shared.ProviderQuota
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.time.Duration

/**
 * GitHub Copilot subscription quota as reported by `copilot_internal/user`.
 *
 * Paid plans report percentage-based snapshots for premium interactions and chat;
 * the free tier reports absolute chat/completions counters. Windows that the API
 * marks as unlimited are kept for snapshots/API output, but display helpers hide them.
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
        subscriptionState != GitHubSubscriptionState.ACTIVE || premiumInteractions != null || chat != null || completions != null

    override fun usageFraction(): Double? {
        return limitedWindows().maxOfOrNull { it.usagePercent }?.let { it / 100.0 }
    }

    override fun activityFraction(): Double? {
        val windows = limitedWindows()
        return windows.takeIf { it.isNotEmpty() }?.sumOf { it.usagePercent }?.let { it / 100.0 }
    }

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
) {
    @Transient
    val periodDuration: Duration? = periodDurationMs?.let(Duration::ofMillis)
}

@Serializable
data class GitHubCredentials(
    val accessToken: String = "",
    val oauthClientId: String = "",
) {
    fun isUsable(): Boolean = accessToken.isNotBlank()
}
