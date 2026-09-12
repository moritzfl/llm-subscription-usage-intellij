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
