package de.moritzf.quota.ollama

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Represents Ollama Cloud subscription usage quota data scraped from ollama.com/settings.
 */
@Serializable
data class OllamaQuota(
    val plan: String = "",
    val sessionUsage: OllamaUsageWindow? = null,
    val weeklyUsage: OllamaUsageWindow? = null,
    var fetchedAt: Instant? = null,
    @Transient var rawJson: String? = null,
) {
    fun hasUsageState(): Boolean {
        return sessionUsage != null || weeklyUsage != null
    }
}

/**
 * Represents a single usage window from the Ollama Cloud subscription.
 */
@Serializable
data class OllamaUsageWindow(
    val usagePercent: Double = 0.0,
    val resetsAt: Instant? = null,
)