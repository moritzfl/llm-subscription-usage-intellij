package de.moritzf.quota.gemini

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class GeminiQuota(
    val buckets: List<GeminiBucket> = emptyList(),
    var planType: String? = null,
    var gcpManaged: Boolean? = null,
    var fetchedAt: Instant? = null,
    @Transient var rawJson: String? = null,
    var accountEmail: String? = null,
    var projectId: String? = null,
    var paidTierName: String? = null
) {
    fun hasUsageState(): Boolean = buckets.isNotEmpty() || planType != null
}

@Serializable
data class GeminiBucket(
    val modelId: String,
    val tokenType: String,
    val remainingAmount: String? = null,
    val remainingFraction: Double? = null,
    val resetTime: String? = null
)
