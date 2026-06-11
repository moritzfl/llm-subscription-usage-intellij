package de.moritzf.quota.github

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.time.Duration

/**
 * GitHub Copilot subscription quota as reported by `copilot_internal/user`.
 *
 * Paid plans report percentage-based snapshots for premium interactions and chat;
 * the free tier reports absolute chat/completions counters. Windows that the API
 * marks as unlimited are kept so the UI can label them instead of showing 0%.
 */
@Serializable
data class GitHubQuota(
    val plan: String = "",
    val premiumInteractions: GitHubUsageWindow? = null,
    val chat: GitHubUsageWindow? = null,
    val completions: GitHubUsageWindow? = null,
    var fetchedAt: Instant? = null,
    @Transient var rawJson: String? = null,
) {
    fun hasUsageState(): Boolean = premiumInteractions != null || chat != null || completions != null

    /** Windows in display priority order, limited ones first. */
    fun limitedWindows(): List<GitHubUsageWindow> =
        listOfNotNull(premiumInteractions, chat, completions).filterNot { it.unlimited }
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
) {
    fun isUsable(): Boolean = accessToken.isNotBlank()
}
