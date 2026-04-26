package de.moritzf.quota.idea

import de.moritzf.quota.JsonSupport
import de.moritzf.quota.OpenAiCodexQuota
import de.moritzf.quota.OpenCodeQuota
import de.moritzf.quota.UsageWindow
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import java.time.Duration

internal object QuotaSnapshotCache {
    fun encodeOpenAiQuota(quota: OpenAiCodexQuota): String? {
        return runCatching { JsonSupport.json.encodeToString(CachedOpenAiQuota.fromQuota(quota)) }.getOrNull()
    }

    fun decodeOpenAiQuota(json: String?): OpenAiCodexQuota? {
        if (json.isNullOrBlank()) return null
        return runCatching { JsonSupport.json.decodeFromString<CachedOpenAiQuota>(json).toQuota() }.getOrNull()
    }

    fun encodeOpenCodeQuota(quota: OpenCodeQuota): String? {
        return runCatching { JsonSupport.json.encodeToString(OpenCodeQuota.serializer(), quota) }.getOrNull()
    }

    fun decodeOpenCodeQuota(json: String?): OpenCodeQuota? {
        if (json.isNullOrBlank()) return null
        return runCatching { JsonSupport.json.decodeFromString(OpenCodeQuota.serializer(), json) }.getOrNull()
    }
}

@Serializable
private data class CachedOpenAiQuota(
    val primary: CachedUsageWindow? = null,
    val secondary: CachedUsageWindow? = null,
    val reviewPrimary: CachedUsageWindow? = null,
    val reviewSecondary: CachedUsageWindow? = null,
    val planType: String? = null,
    val allowed: Boolean? = null,
    val limitReached: Boolean? = null,
    val reviewAllowed: Boolean? = null,
    val reviewLimitReached: Boolean? = null,
    val fetchedAtEpochMs: Long? = null,
    val rawJson: String? = null,
    val accountId: String? = null,
    val email: String? = null,
) {
    fun toQuota(): OpenAiCodexQuota {
        return OpenAiCodexQuota(
            primary = primary?.toUsageWindow(),
            secondary = secondary?.toUsageWindow(),
            reviewPrimary = reviewPrimary?.toUsageWindow(),
            reviewSecondary = reviewSecondary?.toUsageWindow(),
            planType = planType,
            allowed = allowed,
            limitReached = limitReached,
            reviewAllowed = reviewAllowed,
            reviewLimitReached = reviewLimitReached,
            fetchedAt = fetchedAtEpochMs?.let(Instant::fromEpochMilliseconds),
            rawJson = rawJson,
            accountId = accountId,
            email = email,
        )
    }

    companion object {
        fun fromQuota(quota: OpenAiCodexQuota): CachedOpenAiQuota {
            return CachedOpenAiQuota(
                primary = quota.primary?.let(CachedUsageWindow::fromUsageWindow),
                secondary = quota.secondary?.let(CachedUsageWindow::fromUsageWindow),
                reviewPrimary = quota.reviewPrimary?.let(CachedUsageWindow::fromUsageWindow),
                reviewSecondary = quota.reviewSecondary?.let(CachedUsageWindow::fromUsageWindow),
                planType = quota.planType,
                allowed = quota.allowed,
                limitReached = quota.limitReached,
                reviewAllowed = quota.reviewAllowed,
                reviewLimitReached = quota.reviewLimitReached,
                fetchedAtEpochMs = quota.fetchedAt?.toEpochMilliseconds(),
                rawJson = quota.rawJson,
                accountId = quota.accountId,
                email = quota.email,
            )
        }
    }
}

@Serializable
private data class CachedUsageWindow(
    val usedPercent: Double = 0.0,
    val windowDurationMillis: Long? = null,
    val resetsAtEpochMs: Long? = null,
) {
    fun toUsageWindow(): UsageWindow {
        return UsageWindow(
            usedPercent = usedPercent,
            windowDuration = windowDurationMillis?.let(Duration::ofMillis),
            resetsAt = resetsAtEpochMs?.let(Instant::fromEpochMilliseconds),
        )
    }

    companion object {
        fun fromUsageWindow(window: UsageWindow): CachedUsageWindow {
            return CachedUsageWindow(
                usedPercent = window.usedPercent,
                windowDurationMillis = window.windowDuration?.toMillis(),
                resetsAtEpochMs = window.resetsAt?.toEpochMilliseconds(),
            )
        }
    }
}
