package de.moritzf.quota.ollama

import de.moritzf.quota.shared.ProviderQuota
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Ollama Cloud subscription usage from `GET https://ollama.com/api/balance`.
 */
@Serializable
data class OllamaQuota(
    val sessionUsage: OllamaUsageWindow? = null,
    val weeklyUsage: OllamaUsageWindow? = null,
    val monthlyUsage: OllamaUsageWindow? = null,
    override var fetchedAt: Instant? = null,
    @Transient override var rawJson: String? = null,
) : ProviderQuota {
    override fun hasUsageState(): Boolean {
        return sessionUsage != null || weeklyUsage != null || monthlyUsage != null
    }

    override fun usageFraction(): Double? {
        val windows = listOfNotNull(
            sessionUsage?.usagePercent,
            weeklyUsage?.usagePercent,
            monthlyUsage?.usagePercent,
        )
        return windows.maxOrNull()?.let { it / 100.0 }
    }

    override fun activityWindows(): Map<String, Double> = buildMap {
        sessionUsage?.usagePercent?.let { put("session", it / 100.0) }
        weeklyUsage?.usagePercent?.let { put("weekly", it / 100.0) }
        monthlyUsage?.usagePercent?.let { put("monthly", it / 100.0) }
    }
}

/**
 * Single Ollama Cloud usage window. [usagePercent] is 0..100.
 */
@Serializable
data class OllamaUsageWindow(
    val usagePercent: Double = 0.0,
    val resetsAt: Instant? = null,
    val periodStartedAt: Instant? = null,
    val usedAmountUsd: Double? = null,
    val allowanceUsd: Double? = null,
)
