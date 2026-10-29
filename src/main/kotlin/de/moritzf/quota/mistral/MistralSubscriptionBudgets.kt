package de.moritzf.quota.mistral

import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.lenientDoubleOrNull
import java.time.ZoneOffset
import kotlin.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** The subscription page embeds both monthly allowances in Next.js Flight JSON. */
internal object MistralSubscriptionBudgets {
    private const val FLIGHT_MARKER = "self.__next_f.push("
    private val recordId = Regex("[0-9a-fA-F]+")
    private const val LENGTH_DELIMITED_TAGS = "TAOoUSsLlGgMmV"

    /** Return provider JSON only; never retain the HTML or unrelated account data. */
    fun parse(html: String): JsonObject? {
        val stream = flightChunks(html).joinToString("").toByteArray(Charsets.UTF_8)
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
                    collectBudgets(payload, budgets)
                }
            }
            offset = end + 1
        }
        return budgets.singleOrNull()
    }

    /**
     * A regex for the quoted Flight chunk recurses once per character and overflows the stack on
     * the page's long records, so the literal is scanned and decoded step by step.
     */
    private fun flightChunks(html: String): List<String> {
        val chunks = mutableListOf<String>()
        var cursor = 0
        while (cursor < html.length) {
            val marker = html.indexOf(FLIGHT_MARKER, cursor)
            if (marker < 0) break
            var index = skipWhitespace(html, marker + FLIGHT_MARKER.length)
            if (index >= html.length || html[index] != '[') {
                cursor = marker + FLIGHT_MARKER.length
                continue
            }
            index = skipWhitespace(html, index + 1)
            if (index >= html.length || html[index] != '1') {
                cursor = marker + FLIGHT_MARKER.length
                continue
            }
            index = skipWhitespace(html, index + 1)
            if (index >= html.length || html[index] != ',') {
                cursor = marker + FLIGHT_MARKER.length
                continue
            }
            index = skipWhitespace(html, index + 1)
            if (index >= html.length || html[index] != '"') {
                cursor = marker + FLIGHT_MARKER.length
                continue
            }
            val start = index++
            var escaped = false
            while (index < html.length) {
                val character = html[index]
                if (escaped) {
                    escaped = false
                } else if (character == '\\') {
                    escaped = true
                } else if (character == '"') {
                    break
                }
                index++
            }
            if (index >= html.length) return chunks
            val end = index
            var closing = skipWhitespace(html, end + 1)
            if (closing >= html.length || html[closing] != ']') {
                cursor = end + 1
                continue
            }
            closing = skipWhitespace(html, closing + 1)
            if (closing >= html.length || html[closing] != ')') {
                cursor = end + 1
                continue
            }
            val text =
                runCatching {
                    JsonSupport.json.decodeFromString<String>(html.substring(start, end + 1))
                }
                    .getOrNull() ?: return chunks
            chunks += text
            cursor = closing + 1
        }
        return chunks
    }

    private fun skipWhitespace(text: String, start: Int): Int {
        var index = start
        while (index < text.length && text[index].isWhitespace()) index++
        return index
    }

    /**
     * Flight rows can nest component trees deeper than the JSON parser's stack allows, so the
     * budget object is cut out by bracket matching and only that small object is parsed.
     */
    private fun collectBudgets(payload: String, results: MutableSet<JsonObject>) {
        var index = 0
        while (index < payload.length) {
            val keyEnd = findString(payload, "budget", index) ?: return
            var valueStart = skipWhitespace(payload, keyEnd)
            if (valueStart >= payload.length || payload[valueStart] != ':') {
                index = keyEnd
                continue
            }
            valueStart = skipWhitespace(payload, valueStart + 1)
            if (valueStart >= payload.length || payload[valueStart] != '{') {
                index = keyEnd
                continue
            }
            val valueEnd = matchingBrace(payload, valueStart) ?: return
            val budget =
                runCatching {
                    JsonSupport.json.parseToJsonElement(payload.substring(valueStart, valueEnd + 1))
                }
                    .getOrNull() as? JsonObject
            if (
                budget != null &&
                    (window(budget["api_budget"]) != null || window(budget["vibe_budget"]) != null)
            ) {
                results.add(budget)
            }
            index = valueEnd + 1
        }
    }

    /** Returns the index after [expected], or null when it no longer occurs as a JSON string. */
    private fun findString(text: String, expected: String, start: Int): Int? {
        var index = start
        while (index < text.length) {
            if (text[index] != '"') {
                index++
                continue
            }
            val contentStart = index + 1
            index = contentStart
            var escaped = false
            while (index < text.length) {
                val character = text[index]
                if (escaped) {
                    escaped = false
                } else if (character == '\\') {
                    escaped = true
                } else if (character == '"') {
                    break
                }
                index++
            }
            if (index >= text.length) return null
            if (text.substring(contentStart, index) == expected) return index + 1
            index++
        }
        return null
    }

    private fun matchingBrace(text: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val character = text[index]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (character == '\\') {
                    escaped = true
                } else if (character == '"') {
                    inString = false
                }
            } else {
                when (character) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return index
                    }
                }
            }
        }
        return null
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
            normalizeMistralCurrency((budget["currency"] as? JsonPrimitive)?.contentOrNull)
                ?: return null
        val used = (limit * (percent / 100)).takeIf { it.isFinite() } ?: return null
        val reset =
            (budget["reset_at"] as? JsonPrimitive)?.contentOrNull?.let {
                runCatching { Instant.parse(it.removePrefix("\$D")) }.getOrNull()
            }
        val payAsYouGoEnabled = (budget["payg_enabled"] as? JsonPrimitive)?.booleanOrNull
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
            payAsYouGoEnabled = payAsYouGoEnabled,
        )
    }
}
