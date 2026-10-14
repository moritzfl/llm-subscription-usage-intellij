package de.moritzf.quota.litellm

import de.moritzf.quota.shared.ProviderQuota
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable
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
        fun parse(root: JsonObject): LiteLlmQuota {
            val info = root["info"] as? JsonObject ?: error("LiteLLM returned no key information.")
            val now = Clock.System.now()
            val temporary =
                info.nonNegative("temp_budget_increase")?.takeIf {
                    (instant(info.text("temp_budget_expiry")) ?: Instant.DISTANT_PAST) > now
                } ?: 0.0
            // /key/info can echo the key and other credentials. Persist/export only usage fields.
            val safe = JsonObject(info.filterKeys { it in SAFE_FIELDS })
            return LiteLlmQuota(
                spend = info.nonNegative("spend"),
                maxBudget = info.nonNegative("max_budget")?.let { it + temporary },
                resetsAt = instant(info.text("budget_reset_at")),
                blocked = info.bool("blocked") == true,
                fetchedAt = now,
                rawJson = JsonObject(mapOf("info" to safe)).toString(),
            )
        }

        private val SAFE_FIELDS =
            setOf(
                "spend",
                "max_budget",
                "budget_duration",
                "budget_reset_at",
                "temp_budget_increase",
                "temp_budget_expiry",
                "tpm_limit",
                "rpm_limit",
                "blocked",
                "expires",
            )

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
