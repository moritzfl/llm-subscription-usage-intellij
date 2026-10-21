package de.moritzf.quota.litellm

import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.ProviderQuota
import de.moritzf.quota.shared.RawResponseRedactor
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

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

        // Settings-only diagnostics. MCP must use safeQuotaJson instead.
        internal fun responseJson(response: JsonElement, apiKey: String? = null): String =
            RawResponseRedactor.redact(
                    response,
                    setOf("key", "token", "metadata", "config"),
                    apiKey?.trim(),
                )
                .toString()

        /** /key/info is credential metadata, so MCP exports only validated quota scalars. */
        internal fun safeQuotaJson(raw: String): String {
            val root =
                runCatching { JsonSupport.json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            val info = root?.get("info") as? JsonObject
            val safe =
                info.orEmpty().filter { (name, value) ->
                    when {
                        name !in SAFE_FIELDS -> false
                        value is JsonNull -> true
                        value !is JsonPrimitive -> false
                        name in NUMBER_FIELDS ->
                            value.doubleOrNull?.let { it.isFinite() && it >= 0 } == true
                        name == "blocked" -> value.booleanOrNull != null
                        name == "budget_duration" ->
                            value.isString && BUDGET_DURATION.matches(value.content)
                        else -> value.isString && instant(value.content) != null
                    }
                }
            return JsonObject(mapOf("info" to JsonObject(safe))).toString()
        }

        private val NUMBER_FIELDS =
            setOf("spend", "max_budget", "temp_budget_increase", "tpm_limit", "rpm_limit")
        private val SAFE_FIELDS =
            NUMBER_FIELDS +
                setOf(
                    "budget_duration",
                    "budget_reset_at",
                    "temp_budget_expiry",
                    "blocked",
                    "expires",
                )
        private val BUDGET_DURATION = Regex("[0-9]+(?:s|m|h|d|w|mo)")

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
