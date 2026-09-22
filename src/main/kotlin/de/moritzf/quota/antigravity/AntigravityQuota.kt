package de.moritzf.quota.antigravity

import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.ProviderQuota
import de.moritzf.quota.shared.lenientDoubleOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Clock
import kotlin.time.Instant

@Serializable
data class AntigravityQuota(
    val windows: List<AntigravityUsageWindow>,
    val warnings: List<String> = emptyList(),
    override var fetchedAt: Instant? = null,
    override var rawJson: String? = null,
) : ProviderQuota {
    override fun hasUsageState(): Boolean = windows.isNotEmpty()

    fun primaryWindow(): AntigravityUsageWindow? =
        windows.filter { it.usagePercent != null }.maxByOrNull { it.usagePercent!! }

    override fun usageFraction(): Double? = primaryWindow()?.usagePercent?.div(100.0)

    override fun activityWindows(): Map<String, Double> = windows.mapNotNull { window ->
        window.usagePercent?.let { "${window.group}/${window.id}" to it / 100.0 }
    }.toMap()
}

@Serializable
data class AntigravityUsageWindow(
    val id: String,
    val group: String,
    val label: String,
    val window: String? = null,
    val remainingFraction: Double? = null,
    val resetsAt: Instant? = null,
    val disabled: Boolean = false,
) {
    val usagePercent: Double? get() = if (disabled) null else remainingFraction?.let { (1.0 - it) * 100.0 }
}

class AntigravityQuotaException(message: String) : Exception(message)

/** Parse only the CLI's structured command report, never the model's response text. */
internal fun parseAntigravityQuota(raw: String): AntigravityQuota {
    val root = runCatching { JsonSupport.json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        ?: throw AntigravityQuotaException("AGY returned invalid quota JSON. Update AGY and retry.")
    val command = root["command"] as? JsonObject
    if (root.text("status") != "SUCCESS" || command?.text("name") != "usage") {
        throw AntigravityQuotaException("AGY did not return a successful usage report. Run agy to check your sign-in, then refresh.")
    }
    val data = command["data"] as? JsonObject
    val groups = data?.get("groups") as? JsonArray
        ?: throw AntigravityQuotaException("AGY returned no quota groups. Update AGY and retry.")
    val warnings = mutableListOf<String>()
    val windows = buildList {
        for (groupElement in groups) {
            val group = groupElement as? JsonObject
            val buckets = group?.get("buckets") as? JsonArray
            if (group == null || buckets == null) {
                warnings += "An AGY quota group could not be read."
                continue
            }
            for (bucketElement in buckets) {
                val bucket = bucketElement as? JsonObject
                val id = bucket?.text("id")
                if (bucket == null || id == null) {
                    warnings += "An AGY quota window could not be read."
                    continue
                }
                val remainingValue = bucket["remaining_fraction"]
                val remaining = remainingValue?.lenientDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1.0 }
                if (remainingValue.isPresent() && remaining == null) warnings += "Usage unavailable for $id."
                val resetValue = bucket["reset_time"]
                val reset = bucket.text("reset_time")?.let { runCatching { Instant.parse(it) }.getOrNull() }
                if (resetValue.isPresent() && reset == null) warnings += "Reset time unavailable for $id."
                val disabledValue = bucket["disabled"]
                val disabled = (disabledValue as? JsonPrimitive)?.booleanOrNull
                if (disabledValue.isPresent() && disabled == null) {
                    warnings += "Availability unknown for $id."
                }
                add(
                    AntigravityUsageWindow(
                        id = id,
                        group = group.text("name") ?: "Models",
                        label = bucket.text("name") ?: id,
                        window = bucket.text("window"),
                        remainingFraction = if (disabledValue.isPresent() && disabled == null) null else remaining,
                        resetsAt = reset,
                        disabled = disabled == true,
                    ),
                )
            }
        }
    }
    if (windows.isEmpty()) throw AntigravityQuotaException("AGY returned no readable quota windows.")
    return AntigravityQuota(windows, warnings.distinct(), Clock.System.now(), raw)
}

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonElement?.isPresent(): Boolean = this != null && this != JsonNull
