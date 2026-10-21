package de.moritzf.quota.litellm

import de.moritzf.quota.shared.ProviderQuota
import de.moritzf.quota.shared.RawResponseRedactor
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The virtual key's USD budget, not an assertion about inherited team/user limits. */
@Serializable
data class LiteLlmQuota(
    val spend: Double?,
    val maxBudget: Double?,
    val resetsAt: Instant? = null,
    val blocked: Boolean = false,
    override var fetchedAt: Instant? = Clock.System.now(),
    override var rawJson: String? = null,
) : ProviderQuota {
    fun budgetText(): String =
        when {
            blocked -> "Key is blocked."
            spend == null -> "Key spend unavailable."
            maxBudget == null ->
                String.format(java.util.Locale.ROOT, "$%.2f spent · Key budget not reported", spend)
            else ->
                String.format(
                    java.util.Locale.ROOT,
                    "$%.2f of $%.2f key budget used",
                    spend,
                    maxBudget,
                )
        }

    override fun hasUsageState() = spend != null || maxBudget != null || blocked

    override fun usageFraction(): Double? =
        when {
            blocked -> 1.0
            spend == null || maxBudget == null -> null
            maxBudget == 0.0 -> 1.0
            else -> (spend / maxBudget).coerceIn(0.0, 1.0)
        }

    companion object {
        fun parse(root: JsonElement, apiKey: String? = null): LiteLlmQuota {
            val info = (root as? JsonObject)?.get("info") as? JsonObject ?: JsonObject(emptyMap())
            val now = Clock.System.now()
            val temporary =
                info.nonNegative("temp_budget_increase")?.takeIf {
                    (instant(info.text("temp_budget_expiry")) ?: Instant.DISTANT_PAST) > now
                } ?: 0.0
            return LiteLlmQuota(
                spend = info.nonNegative("spend"),
                maxBudget = info.nonNegative("max_budget")?.let { it + temporary },
                resetsAt = instant(info.text("budget_reset_at")),
                blocked = info.bool("blocked") == true,
                fetchedAt = now,
                rawJson = responseJson(root, apiKey),
            )
        }

        // Keep the complete provider document for settings and MCP, including unknown quota fields.
        // Header-only /key/info returns a non-reusable key hash; redact actual echoed credentials.
        internal fun responseJson(response: JsonElement, apiKey: String? = null): String =
            RawResponseRedactor.redact(response, secret = apiKey?.trim()).toString()

        private fun instant(value: String?): Instant? = value?.let {
            runCatching { Instant.parse(it) }.getOrNull()
                ?: runCatching {
                    Instant.parse(
                        java.time.LocalDateTime.parse(it)
                            .toInstant(java.time.ZoneOffset.UTC)
                            .toString()
                    )
                }
                    .getOrNull()
        }
    }
}
