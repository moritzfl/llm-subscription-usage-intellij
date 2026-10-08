package de.moritzf.quota.mistral

import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.lenientDoubleOrNull
import java.time.ZoneOffset
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The subscription page embeds both monthly allowances in Next.js Flight JSON. */
internal object MistralSubscriptionBudgets {
    private val chunk =
        Regex("""self\.__next_f\.push\(\s*\[\s*1\s*,\s*("(?:[^"\\]|\\.)*")\s*]\s*\)""")
    private val recordId = Regex("[0-9a-fA-F]+")
    private const val LENGTH_DELIMITED_TAGS = "TAOoUSsLlGgMmV"

    /** Return provider JSON only; never retain the HTML or unrelated account data. */
    fun parse(html: String): JsonObject? {
        val stream = buildString {
            for (match in chunk.findAll(html)) {
                val text =
                    runCatching { JsonSupport.json.decodeFromString<String>(match.groupValues[1]) }
                        .getOrNull() ?: return null
                append(text)
            }
        }
            .toByteArray(Charsets.UTF_8)
        val budgets = mutableSetOf<JsonObject>()
        var offset = 0
        while (offset < stream.size) {
            var end = offset
            while (end < stream.size && stream[end] != '\n'.code.toByte()) end++
            val line = stream.decodeToString(offset, end)
            val colon = line.indexOf(':')
            if (colon > 0 && recordId.matches(line.substring(0, colon))) {
                val payload = line.substring(colon + 1)
                if (payload.firstOrNull()?.let { it in LENGTH_DELIMITED_TAGS } == true) {
                    // Flight text/binary records are byte-counted, and may contain newlines or fake
                    // JSON rows.
                    val comma = payload.indexOf(',')
                    if (comma < 2) return null
                    val length =
                        payload.substring(1, comma).toIntOrNull(16)?.takeIf { it >= 0 }
                            ?: return null
                    val start = offset + colon + 1 + comma + 1
                    if (length > stream.size - start) return null
                    offset = start + length
                    continue
                }
                if (payload.startsWith('{') || payload.startsWith('[')) {
                    val root =
                        runCatching { JsonSupport.json.parseToJsonElement(payload) }.getOrNull()
                            ?: return null
                    collectBudgets(root, budgets)
                }
            }
            offset = end + 1
        }
        return budgets.singleOrNull()
    }

    private fun collectBudgets(value: JsonElement, results: MutableSet<JsonObject>) {
        when (value) {
            is JsonObject -> {
                (value["budget"] as? JsonObject)
                    ?.takeIf {
                        window(it["api_budget"]) != null || window(it["vibe_budget"]) != null
                    }
                    ?.let(results::add)
                value.values.forEach { collectBudgets(it, results) }
            }
            is JsonArray -> value.forEach { collectBudgets(it, results) }
            else -> Unit
        }
    }

    fun window(value: JsonElement?): MistralUsageWindow? {
        val budget = value as? JsonObject ?: return null
        val percent =
            budget["usage_percentage"]?.lenientDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                ?: return null
        val limit =
            budget["initial_budget"]?.lenientDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
                ?: return null
        val currency =
            (budget["currency"] as? JsonPrimitive)
                ?.contentOrNull
                ?.trim()
                ?.uppercase(java.util.Locale.ROOT)
                ?.takeIf { runCatching { java.util.Currency.getInstance(it) }.isSuccess }
                ?: return null
        val used = (limit * (percent / 100)).takeIf { it.isFinite() } ?: return null
        val reset =
            (budget["reset_at"] as? JsonPrimitive)?.contentOrNull?.let {
                runCatching { Instant.parse(it) }.getOrNull()
            }
        val periodMs = reset?.let {
            val end =
                java.time.Instant.ofEpochMilli(it.toEpochMilliseconds()).atZone(ZoneOffset.UTC)
            java.time.Duration.between(end.minusMonths(1), end).toMillis()
        }
        return MistralUsageWindow(
            usagePercent = percent,
            resetsAt = reset,
            periodDurationMs = periodMs,
            usedAmount = used,
            limitAmount = limit,
            currency = currency,
        )
    }
}
