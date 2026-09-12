package de.moritzf.proxy.fim

object CompletionSanitizer {
    private val FENCE_OPEN = Regex("^```[A-Za-z0-9_+-]*\\s*\\n")
    private val FENCE_CLOSE = Regex("\\n```\\s*$")
    private val WRAPPED_QUOTES = Regex("^([\"'])(.*)\\1$", RegexOption.DOT_MATCHES_ALL)
    private val LEFTOVER_TOKENS = Regex(
        "<\\|fim_[a-z]+\\|>|<fim_[a-z]+>|<｜fim▁[^｜]*｜>|\\[PREFIX]|\\[SUFFIX]|\\[MIDDLE]|<PRE>|<SUF>|<MID>|<CURSOR>|</code_[a-z_]+>|<code_[a-z_]+>",
    )
    private val APOLOGY_START = Regex("^(sure|here is|here's|i will|the code)\\b", RegexOption.IGNORE_CASE)
    internal val INTERNAL_STOPS = listOf("\n\n\n", "```", "<|fim_", "<fim_", "<CURSOR", "</code_")

    fun sanitize(
        raw: String,
        prefix: String = "",
        suffix: String = "",
        stop: List<String> = emptyList(),
    ): String {
        var text = raw.removePrefix("\uFEFF").replace("\r\n", "\n")
        text = stripFence(text)
        text = stripWrappedQuotes(text)
        text = LEFTOVER_TOKENS.replace(text, "")
        val stopCut = cutAtStopSequence(text, stop + INTERNAL_STOPS)
        if (stopCut != null) {
            text = stopCut.content
        }
        text = dropPrefixOverlap(text, prefix)
        text = dropSuffixOverlap(text, suffix)
        if (looksLikeExplanation(text)) return ""
        return text
    }

    fun looksLikeExplanation(text: String): Boolean {
        val trimmed = text.trimStart()
        if (trimmed.isEmpty()) return false
        if (APOLOGY_START.containsMatchIn(trimmed)) return true
        val lines = text.lines()
        if (lines.size > 4) {
            val prose = lines.any { line ->
                val value = line.trim()
                value.length > 40 &&
                    value.contains(' ') &&
                    value.endsWith('.') &&
                    value.none { it in "{}();=<>" }
            }
            if (prose) return true
        }
        return false
    }

    fun cutAtStopSequence(text: String, stopSequences: List<String>): StopCut? {
        var earliestStart = -1
        var fired: String? = null
        for (sequence in stopSequences) {
            if (sequence.isEmpty()) continue
            val start = text.indexOf(sequence)
            if (start < 0) continue
            if (earliestStart < 0 || start < earliestStart) {
                earliestStart = start
                fired = sequence
            }
        }
        return if (fired != null) StopCut(text.substring(0, earliestStart), fired) else null
    }

    private fun stripFence(text: String): String {
        var value = text.trim('\n')
        val open = FENCE_OPEN.find(value)
        if (open != null) {
            value = value.substring(open.range.last + 1)
            value = FENCE_CLOSE.replace(value, "")
            return value
        }
        if (value.startsWith("```") && value.endsWith("```") && value.length > 6) {
            val firstNewline = value.indexOf('\n')
            val withoutOpen = if (firstNewline > 0) value.substring(firstNewline + 1) else value.drop(3)
            return withoutOpen.removeSuffix("```").trimEnd()
        }
        return text
    }

    private fun stripWrappedQuotes(text: String): String {
        val match = WRAPPED_QUOTES.matchEntire(text.trim()) ?: return text
        val inner = match.groupValues[2]
        if (inner.contains('\n') || inner.length > 8) return inner
        return text
    }

    internal fun dropPrefixOverlap(output: String, prefix: String): String {
        if (output.isEmpty() || prefix.isEmpty()) return output
        val max = minOf(64, output.length, prefix.length)
        for (n in max downTo 8) {
            if (output.startsWith(prefix.takeLast(n))) return output.drop(n)
        }
        return output
    }

    internal fun dropSuffixOverlap(output: String, suffix: String): String {
        if (output.isEmpty() || suffix.isEmpty()) return output
        val max = minOf(64, output.length, suffix.length)
        for (n in max downTo 8) {
            if (output.startsWith(suffix.take(n))) return output.drop(n)
        }
        return output
    }

    data class StopCut(val content: String, val sequence: String)
}

class StreamingCompletionSanitizer(
    private val prefix: String,
    private val suffix: String,
    private val stop: List<String>,
    private val holdChars: Int = 32,
) {
    private val raw = StringBuilder()
    private val emitted = StringBuilder()
    private var finished = false

    fun push(delta: String): String {
        if (finished || delta.isEmpty()) return ""
        raw.append(delta)
        if (raw.length < holdChars) return ""
        return flushDelta()
    }

    fun finish(): String {
        finished = true
        return flushDelta()
    }

    private fun flushDelta(): String {
        val sanitized = CompletionSanitizer.sanitize(raw.toString(), prefix, suffix, stop)
        if (sanitized.length <= emitted.length) return ""
        val extra = sanitized.substring(emitted.length)
        emitted.append(extra)
        return extra
    }
}
