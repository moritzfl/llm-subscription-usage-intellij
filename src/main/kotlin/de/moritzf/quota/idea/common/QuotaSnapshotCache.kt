package de.moritzf.quota.idea.common

import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.openai.OpenAiCredits
import de.moritzf.quota.openai.OpenAiSpendControl
import de.moritzf.quota.openai.RateLimitResetCredit
import de.moritzf.quota.openai.UsageWindow
import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.ProviderQuota
import kotlin.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.time.Duration

/**
 * Encodes provider quotas to JSON for persistent caching and decodes them back.
 * New providers register a single codec entry in [codecs].
 */
internal object QuotaSnapshotCache {
    @Suppress("UNCHECKED_CAST")
    private fun codecFor(type: QuotaProviderType): QuotaCodec<ProviderQuota>? =
        QuotaProviderRegistry.getOrNull(type)?.snapshotCodec as QuotaCodec<ProviderQuota>?

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
internal class EnvelopeQuotaCodec<Q : ProviderQuota>(
    private val serializer: KSerializer<Q>,
) : QuotaCodec<Q> {
    private val envelopeSerializer = CachedQuotaEnvelope.serializer(serializer)

    override fun encode(quota: Q): String? {
        val envelope = CachedQuotaEnvelope(quota, RawResponseRedactor.redact(quota.rawJson))
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
internal class PlainQuotaCodec<Q : ProviderQuota>(
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

internal object RawResponseRedactor {
    private const val REDACTED = "[REDACTED]"
    private val exactSensitiveNames = setOf(
        "authorization",
        "proxy_authorization",
        "cookie",
        "set_cookie",
        "access_token",
        "refresh_token",
        "id_token",
        "api_key",
        "apikey",
        "password",
        "secret",
    )
    private val tokenCountNames = setOf(
        "total_tokens",
        "input_tokens",
        "output_tokens",
        "prompt_tokens",
        "completion_tokens",
        "cached_tokens",
        "reasoning_tokens",
    )
    private val sensitiveNameFragments = listOf("secret", "password", "cookie")

    fun redact(raw: String?): String? {
        if (raw.isNullOrBlank()) return raw
        val element = runCatching { JsonSupport.json.parseToJsonElement(raw) }.getOrNull() ?: return raw
        return runCatching { redactElement(element).toString() }.getOrDefault(raw)
    }

    private fun redactElement(element: JsonElement): JsonElement {
        return when (element) {
            is JsonObject -> buildJsonObject {
                element.forEach { (key, value) ->
                    put(key, if (isSensitiveName(key)) JsonPrimitive(REDACTED) else redactElement(value))
                }
            }
            is JsonArray -> buildJsonArray {
                element.forEach { add(redactElement(it)) }
            }
            else -> element
        }
    }

    private fun isSensitiveName(name: String): Boolean {
        val normalized = name
            .replace(Regex("([a-z])([A-Z])"), "$1_$2")
            .lowercase()
            .replace('-', '_')
        return normalized in exactSensitiveNames ||
            normalized.endsWith("_key") ||
            normalized.endsWith("key") && normalized.startsWith("api") ||
            normalized.endsWith("_token") && normalized !in tokenCountNames ||
            sensitiveNameFragments.any { normalized.contains(it) }
    }
}

/** OpenAI keeps a bespoke cache shape that flattens windows to epoch-millis timestamps. */
internal object OpenAiQuotaCodec : QuotaCodec<OpenAiCodexQuota> {
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
    val resetCreditsAvailableCount: Int = 0,
    val resetCredits: List<RateLimitResetCredit> = emptyList(),
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
            resetCreditsAvailableCount = resetCreditsAvailableCount,
            resetCredits = resetCredits,
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
                resetCreditsAvailableCount = quota.resetCreditsAvailableCount,
                resetCredits = quota.resetCredits,
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
