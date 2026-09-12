package de.moritzf.proxy.fim

import kotlin.test.Test
import kotlin.test.assertEquals

class CompletionSanitizerTest {
    @Test
    fun stripsMarkdownFences() {
        val raw = "```kotlin\nprintln(1)\n```"

        assertEquals("println(1)", CompletionSanitizer.sanitize(raw))
    }

    @Test
    fun stripsPrefixOverlap() {
        val prefix = "fun add(a: Int, b: Int): Int {\n    return "
        val raw = "    return a + b"

        assertEquals("a + b", CompletionSanitizer.sanitize(raw, prefix = prefix))
    }

    @Test
    fun stripsSuffixOverlap() {
        val suffix = "\n}\nfun other() {}"
        val raw = "\n}\nfun other() {}\n extra"

        assertEquals("\n extra", CompletionSanitizer.sanitize(raw, suffix = suffix))
    }

    @Test
    fun rejectsApology() {
        val raw = "Sure, here is the rest of the function:\nreturn a + b"

        assertEquals("", CompletionSanitizer.sanitize(raw))
    }

    @Test
    fun cutsAtStopSequence() {
        val raw = "a + b\n\n\nmore"

        assertEquals("a + b", CompletionSanitizer.sanitize(raw, stop = listOf("\n\n\n")))
    }

    @Test
    fun stripsLeftoverFimTokens() {
        val raw = "a + b<|fim_middle|>"

        assertEquals("a + b", CompletionSanitizer.sanitize(raw))
    }

    @Test
    fun stripsTrailingCommentCloserEcho() {
        val prefix = "/**\n * Returns "
        val suffix = "\n */\nfun add(int: Int, other: Int): Int {\n    return int + other\n}\n"
        val raw = "the sum of two integers.\n */"

        assertEquals(
            "the sum of two integers.",
            CompletionSanitizer.sanitize(raw, prefix = prefix, suffix = suffix),
        )
    }

    @Test
    fun keepsKdocProseWhenCursorIsInComment() {
        val prefix = "/**\n * Returns "
        val raw = "the sum of the two integer addends together.\n *\n * @param int first addend.\n * @param other second addend.\n * @return combined value."

        assertEquals(raw, CompletionSanitizer.sanitize(raw, prefix = prefix))
        assertEquals("", CompletionSanitizer.sanitize(raw))
    }

    @Test
    fun stillRejectsApologyInsideComment() {
        val prefix = "/**\n * Returns "
        val raw = "Sure, here is a docstring:\nthe sum"

        assertEquals("", CompletionSanitizer.sanitize(raw, prefix = prefix))
    }

    @Test
    fun detectsWhetherCommentContinuesAfterCursor() {
        assertEquals(false, CompletionSanitizer.commentContinuesAfterCursor("\n */\nfun add() {}"))
        assertEquals(false, CompletionSanitizer.commentContinuesAfterCursor("\nfun add() {}"))
        assertEquals(true, CompletionSanitizer.commentContinuesAfterCursor("\n * the sum of a and b.\n */"))
        assertEquals(true, CompletionSanitizer.commentContinuesAfterCursor("\n// more\nfun add() {}"))
    }

    @Test
    fun dropsBlankLineStopOutsideCommentsOnly() {
        val stops = CompletionSanitizer.effectiveStops("fun add() {\n    return ", listOf("\n\n", "<|fim_middle|>"))
        val commentStops = CompletionSanitizer.effectiveStops("/**\n * Returns ", listOf("\n\n", "<|fim_middle|>"))

        assertEquals(true, "\n\n" in stops)
        assertEquals(false, "\n\n" in commentStops)
    }

    @Test
    fun streamingHoldsThenEmitsSanitizedText() {
        val sanitizer = StreamingCompletionSanitizer(
            prefix = "",
            suffix = "",
            stop = emptyList(),
            holdChars = 8,
        )

        assertEquals("", sanitizer.push("```kt\n"))
        assertEquals("hello", sanitizer.push("hello"))
        assertEquals("", sanitizer.finish())
    }
}
