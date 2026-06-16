package de.moritzf.quota.idea.common

import de.moritzf.quota.cursor.CursorQuota
import de.moritzf.quota.github.GitHubQuota
import de.moritzf.quota.kimi.KimiQuota
import de.moritzf.quota.minimax.MiniMaxQuota
import de.moritzf.quota.ollama.OllamaQuota
import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.openai.OpenAiCredits
import de.moritzf.quota.openai.OpenAiSpendControl
import de.moritzf.quota.openai.UsageWindow
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.ProviderQuota
import de.moritzf.quota.supergrok.SuperGrokQuota
import de.moritzf.quota.zai.ZaiQuota
import kotlinx.datetime.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import java.time.Duration

/**
 * Encodes provider quotas to JSON for persistent caching and decodes them back.
 * New providers register a single codec entry in [codecs].
 */
internal object QuotaSnapshotCache {
    private val codecs: Map<QuotaProviderType, QuotaCodec<out ProviderQuota>> = mapOf(
        QuotaProviderType.OPEN_AI to OpenAiQuotaCodec,
        QuotaProviderType.OPEN_CODE to PlainQuotaCodec(OpenCodeQuota.serializer()),
        QuotaProviderType.OLLAMA to EnvelopeQuotaCodec(OllamaQuota.serializer()),
        QuotaProviderType.ZAI to EnvelopeQuotaCodec(ZaiQuota.serializer()),
        QuotaProviderType.MINIMAX to EnvelopeQuotaCodec(MiniMaxQuota.serializer()),
        QuotaProviderType.KIMI to EnvelopeQuotaCodec(KimiQuota.serializer()),
        QuotaProviderType.GITHUB to EnvelopeQuotaCodec(GitHubQuota.serializer()),
        QuotaProviderType.CURSOR to EnvelopeQuotaCodec(CursorQuota.serializer()),
        QuotaProviderType.SUPERGROK to EnvelopeQuotaCodec(SuperGrokQuota.serializer()),
    )

    @Suppress("UNCHECKED_CAST")
    private fun codecFor(type: QuotaProviderType): QuotaCodec<ProviderQuota>? =
        codecs[type] as QuotaCodec<ProviderQuota>?

    fun encode(type: QuotaProviderType, quota: ProviderQuota): String? = codecFor(type)?.encode(quota)

    fun decode(type: QuotaProviderType, json: String?): ProviderQuota? {
        if (json.isNullOrBlank()) return null
        return codecFor(type)?.decode(json)
    }

    /** Serializes the bare quota payload; used as a raw-JSON fallback for display. */
    fun encodePlain(type: QuotaProviderType, quota: ProviderQuota): String? = codecFor(type)?.encodePlain(quota)
}

internal interface QuotaCodec<Q : ProviderQuota> {
    fun encode(quota: Q): String?
    fun decode(json: String): Q?
    fun encodePlain(quota: Q): String? = null
}

/** Persists the quota together with its transient raw upstream response. */
private class EnvelopeQuotaCodec<Q : ProviderQuota>(
    private val serializer: KSerializer<Q>,
) : QuotaCodec<Q> {
    private val envelopeSerializer = CachedQuotaEnvelope.serializer(serializer)

    override fun encode(quota: Q): String? {
        val envelope = CachedQuotaEnvelope(quota, quota.rawJson)
        return runCatching { JsonSupport.json.encodeToString(envelopeSerializer, envelope) }.getOrNull()
    }

    override fun decode(json: String): Q? {
        val fromEnvelope = runCatching {
            val envelope = JsonSupport.json.decodeFromString(envelopeSerializer, json)
            envelope.quota.apply { rawJson = envelope.rawResponse }
        }.getOrNull()
        return fromEnvelope
            ?: runCatching { JsonSupport.json.decodeFromString(serializer, json) }.getOrNull()
    }

    override fun encodePlain(quota: Q): String? {
        return runCatching { JsonSupport.json.encodeToString(serializer, quota) }.getOrNull()
    }
}

/** Persists the bare quota payload without an envelope. */
private class PlainQuotaCodec<Q : ProviderQuota>(
    private val serializer: KSerializer<Q>,
) : QuotaCodec<Q> {
    override fun encode(quota: Q): String? {
        return runCatching { JsonSupport.json.encodeToString(serializer, quota) }.getOrNull()
    }

    override fun decode(json: String): Q? {
        return runCatching { JsonSupport.json.decodeFromString(serializer, json) }.getOrNull()
    }
}

@Serializable
private data class CachedQuotaEnvelope<Q>(
    val quota: Q,
    val rawResponse: String? = null,
)

/** OpenAI keeps a bespoke cache shape that flattens windows to epoch-millis timestamps. */
private object OpenAiQuotaCodec : QuotaCodec<OpenAiCodexQuota> {
    override fun encode(quota: OpenAiCodexQuota): String? {
        return runCatching { JsonSupport.json.encodeToString(CachedOpenAiQuota.fromQuota(quota)) }.getOrNull()
    }

    override fun decode(json: String): OpenAiCodexQuota? {
        return runCatching { JsonSupport.json.decodeFromString<CachedOpenAiQuota>(json).toQuota() }.getOrNull()
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
    val accountId: String? = null,
    val email: String? = null,
    val credits: OpenAiCredits? = null,
    val spendControl: OpenAiSpendControl? = null,
    val rateLimitReachedType: String? = null,
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
            accountId = accountId,
            email = email,
            credits = credits,
            spendControl = spendControl,
            rateLimitReachedType = rateLimitReachedType,
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
                accountId = quota.accountId,
                email = quota.email,
                credits = quota.credits,
                spendControl = quota.spendControl,
                rateLimitReachedType = quota.rateLimitReachedType,
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
