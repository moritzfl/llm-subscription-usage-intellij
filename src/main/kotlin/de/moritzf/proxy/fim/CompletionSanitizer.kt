package de.moritzf.proxy.fim

object CompletionSanitizer {
    private val FENCE_OPEN = Regex("^```[A-Za-z0-9_+-]*\\s*\\n")
    private val FENCE_CLOSE = Regex("\\n```\\s*$")
    private val WRAPPED_QUOTES = Regex("^([\"'])(.*)\\1$", RegexOption.DOT_MATCHES_ALL)
    private val LEFTOVER_TOKENS = Regex(
        "<\\|fim_[a-z]+\\|>|<fim_[a-z]+>|<｜fim▁[^｜]*｜>|\\[PREFIX]|\\[SUFFIX]|\\[MIDDLE]|<PRE>|<SUF>|<MID>|<CURSOR>|</code_before_cursor>|</code_after_cursor>|<code_before_cursor>|<code_after_cursor>",
    )
    private val APOLOGY_START = Regex("^(sure|here is|here's|i will|the code)\\b", RegexOption.IGNORE_CASE)
    internal val INTERNAL_STOPS = listOf(
        "\n\n\n",
        "<|fim_",
        "<fim_",
        "<CURSOR",
        "</code_before_cursor>",
        "</code_after_cursor>",
    )

    fun sanitize(
        raw: String,
        prefix: String = "",
        suffix: String = "",
        stop: List<String> = emptyList(),
        languageHint: String? = null,
    ): String {
        var text = raw.removePrefix("\uFEFF").replace("\r\n", "\n")
        if (shouldStripFence(prefix)) {
            text = stripFence(text)
        }
        text = stripWrappedQuotes(text, prefix)
        val stopCut = earliestCut(text, stop + INTERNAL_STOPS)
        if (stopCut != null) {
            text = stopCut.content
        }
        text = LEFTOVER_TOKENS.replace(text, "")
        text = dropPrefixOverlap(text, prefix)
        text = dropCurrentLineOverlap(text, prefix)
        text = dropPunctuationOverlap(text, prefix)
        text = dropSuffixOverlap(text, suffix)
        text = stripRedundantCurrentLineIndent(text, prefix)
        text = stripRedundantCommentMarker(text, prefix, languageHint)
        if (looksLikeExplanation(text, prefix, languageHint)) return ""
        return text
    }

    fun looksLikeExplanation(text: String, prefix: String = "", languageHint: String? = null): Boolean {
        val trimmed = text.trimStart()
        if (trimmed.isEmpty()) return false
        if (APOLOGY_START.containsMatchIn(trimmed)) return true
        if (isCommentHole(prefix, languageHint)) return false
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

    fun isCommentHole(prefix: String, languageHint: String? = null): Boolean {
        return FimCommentContext.detect(prefix, languageHint) != null
    }

    fun commentContinuesAfterCursor(suffix: String): Boolean {
        val line = suffix.lineSequence().firstOrNull { it.isNotBlank() } ?: return false
        val trimmed = line.trimStart()
        if (trimmed.startsWith("*/") || trimmed.startsWith("-->")) return false
        return trimmed.startsWith("*") ||
            trimmed.startsWith("//") ||
            trimmed.startsWith("/*") ||
            trimmed.startsWith("#") ||
            trimmed.startsWith("--") ||
            trimmed.startsWith("<!--")
    }

    fun effectiveStops(prefix: String, clientStops: List<String>, languageHint: String? = null): List<String> {
        val stops = (clientStops + INTERNAL_STOPS).distinct()
        return if (isCommentHole(prefix, languageHint)) stops.filter { it != "\n\n" } else stops
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

    internal fun dropUnstableTail(text: String, stopSequences: List<String>): String {
        val stops = stopSequences.filter { it.isNotEmpty() }
        if (text.isEmpty() || stops.isEmpty()) return text
        val max = stops.maxOf { it.length }.coerceAtMost(text.length)
        var hold = 0
        for (n in 1..max) {
            val suffix = text.takeLast(n)
            if (stops.any { it.startsWith(suffix) }) hold = n
        }
        return if (hold == 0) text else text.dropLast(hold)
    }

    private fun earliestCut(text: String, stopSequences: List<String>): StopCut? {
        val stopCut = cutAtStopSequence(text, stopSequences)
        val token = LEFTOVER_TOKENS.find(text) ?: return stopCut
        if (stopCut == null || token.range.first < stopCut.content.length) {
            return StopCut(text.substring(0, token.range.first), token.value)
        }
        return stopCut
    }

    private fun shouldStripFence(prefix: String): Boolean {
        val last = prefix.lineSequence().lastOrNull { it.isNotBlank() }?.trimStart() ?: return true
        return !last.startsWith("#") && !last.startsWith("<") && !last.startsWith("```")
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

    private fun stripWrappedQuotes(text: String, prefix: String): String {
        val match = WRAPPED_QUOTES.matchEntire(text.trim()) ?: return text
        val inner = match.groupValues[2]
        if (!inner.contains('\n')) return text
        if (looksLikeStringLiteralPrefix(prefix)) return text
        return inner
    }

    private fun looksLikeStringLiteralPrefix(prefix: String): Boolean {
        val trimmed = prefix.trimEnd()
        if (trimmed.isEmpty()) return false
        val last = trimmed.last()
        return last == '=' || last == '(' || last == '+' || last == ':' || last == '"' || last == '\'' || last == '`'
    }

    internal fun stripRedundantCommentMarker(
        output: String,
        prefix: String,
        languageHint: String? = null,
    ): String {
        if (output.isEmpty()) return output
        val comment = FimCommentContext.detect(prefix, languageHint) ?: return output
        val lineStart = prefix.lastIndexOf('\n') + 1
        if (comment.markerOffset < lineStart) return output
        val remainder = prefix.substring(comment.markerOffset + comment.marker.length)
        if (remainder.isNotBlank()) return output
        val firstContent = output.indexOfFirst { it != ' ' && it != '\t' }
        if (firstContent < 0) return output
        val afterWs = output.substring(firstContent)
        val marker = comment.marker
        if (!afterWs.startsWith(marker)) return output
        if (marker == "*" && (afterWs.startsWith("*/") || afterWs.startsWith("**"))) return output
        if (marker == "/*" && afterWs.startsWith("/**")) return output
        var rest = afterWs.substring(marker.length)
        if (rest.startsWith(" ") && prefix.endsWith(" ")) {
            rest = rest.substring(1)
        }
        return rest
    }

    internal fun dropPrefixOverlap(output: String, prefix: String): String {
        if (output.isEmpty() || prefix.isEmpty()) return output
        val max = minOf(64, output.length, prefix.length)
        for (n in max downTo 8) {
            val piece = prefix.takeLast(n)
            if (piece.isBlank()) continue
            if (output.startsWith(piece)) return output.drop(n)
        }
        return output
    }

    internal fun dropCurrentLineOverlap(output: String, prefix: String): String {
        val lastLine = prefix.substringAfterLast('\n')
        val trimmedStart = lastLine.trimStart()
        if (trimmedStart.length >= 2 && output.startsWith(trimmedStart)) return output.drop(trimmedStart.length)
        val trimmed = lastLine.trim()
        if (trimmed.length >= 2 && output == trimmed) return ""
        return output
    }

    internal fun dropPunctuationOverlap(output: String, prefix: String): String {
        if (output.isEmpty() || prefix.isEmpty()) return output
        if (prefix.endsWith("://") && output.startsWith("://")) return output.drop(3)
        if (prefix.trimEnd().endsWith("=") && output.startsWith("=")) {
            var rest = output.drop(1)
            if (prefix.endsWith(" ") && rest.startsWith(" ")) rest = rest.drop(1)
            return rest
        }
        return output
    }

    internal fun stripRedundantCurrentLineIndent(output: String, prefix: String): String {
        val lastLine = prefix.substringAfterLast('\n')
        if (lastLine.isEmpty() || lastLine.any { !it.isWhitespace() }) return output
        if (output.startsWith(lastLine)) return output.drop(lastLine.length)
        return output
    }

    internal fun dropSuffixOverlap(output: String, suffix: String): String {
        if (output.isEmpty() || suffix.isEmpty()) return output
        val max = minOf(64, output.length, suffix.length)
        for (n in max downTo 2) {
            val piece = suffix.take(n)
            if (!isSafeSuffixOverlap(piece)) continue
            if (output.startsWith(piece)) return output.drop(n)
        }
        for (n in max downTo 2) {
            val piece = suffix.take(n)
            if (!isSafeSuffixOverlap(piece)) continue
            if (output.endsWith(piece)) return output.dropLast(n)
        }
        return output
    }

    private fun isSafeSuffixOverlap(piece: String): Boolean {
        if (piece.contains('\n')) return true
        val trimmed = piece.trim()
        return trimmed == "*/" || trimmed == "-->"
    }

    data class StopCut(val content: String, val sequence: String)
}

class StreamingCompletionSanitizer(
    private val prefix: String,
    private val suffix: String,
    private val stop: List<String>,
    private val holdChars: Int = 32,
    private val languageHint: String? = null,
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
        val sanitized = CompletionSanitizer.sanitize(raw.toString(), prefix, suffix, stop, languageHint)
        val stable = if (finished) {
            sanitized
        } else {
            CompletionSanitizer.dropUnstableTail(sanitized, stop + CompletionSanitizer.INTERNAL_STOPS)
        }
        val already = emitted.toString()
        if (stable.length < already.length || !stable.startsWith(already)) return ""
        val extra = stable.substring(already.length)
        emitted.append(extra)
        return extra
    }
}
