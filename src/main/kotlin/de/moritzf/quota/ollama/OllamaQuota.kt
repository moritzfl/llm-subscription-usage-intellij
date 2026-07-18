package de.moritzf.quota.ollama

import de.moritzf.quota.shared.ProviderQuota
import kotlin.time.Instant
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
    override var fetchedAt: Instant? = null,
    @Transient override var rawJson: String? = null,
) : ProviderQuota {
    override fun hasUsageState(): Boolean {
        return sessionUsage != null || weeklyUsage != null
    }

    override fun usageFraction(): Double? {
        val windows = listOfNotNull(sessionUsage?.usagePercent, weeklyUsage?.usagePercent)
        return windows.maxOrNull()?.let { it / 100.0 }
    }

    override fun activityFraction(): Double? {
        val windows = listOfNotNull(sessionUsage?.usagePercent, weeklyUsage?.usagePercent)
        return windows.takeIf { it.isNotEmpty() }?.sum()?.let { it / 100.0 }
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
