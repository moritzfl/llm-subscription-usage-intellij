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
    fun stripsRedundantJavadocStarWhenLineAlreadyHasOne() {
        val prefix = "/**\n * "
        val raw = "* Aktiviert diese Migration."

        assertEquals(
            "Aktiviert diese Migration.",
            CompletionSanitizer.sanitize(raw, prefix = prefix),
        )
    }

    @Test
    fun stripsRedundantStarWithLeadingSpace() {
        val prefix = "/**\n * "
        val raw = " * Aktiviert diese Migration."

        assertEquals(
            "Aktiviert diese Migration.",
            CompletionSanitizer.sanitize(raw, prefix = prefix),
        )
    }

    @Test
    fun keepsStarOnFollowingCommentLines() {
        val prefix = "/**\n * "
        val raw = "* Aktiviert diese Migration.\n * @return nothing"

        assertEquals(
            "Aktiviert diese Migration.\n * @return nothing",
            CompletionSanitizer.sanitize(raw, prefix = prefix),
        )
    }

    @Test
    fun doesNotStripCommentCloser() {
        val prefix = "/**\n * "

        assertEquals("*/", CompletionSanitizer.sanitize("*/", prefix = prefix))
    }

    @Test
    fun keepsStarWhenCurrentLineHasNoStarYet() {
        val prefix = "/**\n"
        val raw = " * Aktiviert"

        assertEquals(" * Aktiviert", CompletionSanitizer.sanitize(raw, prefix = prefix))
    }

    @Test
    fun doesNotStripStarOutsideComments() {
        val prefix = "val x = "

        assertEquals("* 2", CompletionSanitizer.sanitize("* 2", prefix = prefix))
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
    fun stripsRedundantLineCommentMarker() {
        val prefix = "    // "

        assertEquals("TODO: handle this", CompletionSanitizer.sanitize("// TODO: handle this", prefix = prefix))
        assertEquals("TODO: handle this", CompletionSanitizer.sanitize("TODO: handle this", prefix = prefix))
    }

    @Test
    fun stripsRedundantTrailingInlineCommentMarker() {
        val prefix = "    foo(); // "

        assertEquals("note", CompletionSanitizer.sanitize("// note", prefix = prefix))
        assertEquals("note", CompletionSanitizer.sanitize("note", prefix = prefix))
        assertEquals(true, CompletionSanitizer.isCommentHole(prefix))
        assertEquals(false, "\n\n" in CompletionSanitizer.effectiveStops(prefix, listOf("\n\n")))
    }

    @Test
    fun doesNotTreatHttpSchemeAsComment() {
        val prefix = "val url = \"http://"

        assertEquals(false, CompletionSanitizer.isCommentHole(prefix))
        assertEquals("example.com\"", CompletionSanitizer.sanitize("example.com\"", prefix = prefix))
    }

    @Test
    fun stripsRedundantHashAndSqlCommentMarkers() {
        assertEquals("hint", CompletionSanitizer.sanitize("# hint", prefix = "    # "))
        assertEquals("col", CompletionSanitizer.sanitize("-- col", prefix = "SELECT 1 -- "))
    }

    @Test
    fun stripsRedundantBlockCommentMarkerOnSameLine() {
        assertEquals("n", CompletionSanitizer.sanitize("/* n", prefix = "foo(/* "))
    }

    @Test
    fun emptyLineAfterLineCommentIsNotACommentHole() {
        assertEquals(false, CompletionSanitizer.isCommentHole("    foo(); // done\n"))
    }

    @Test
    fun emptyLineInsideUnclosedBlockCommentIsACommentHole() {
        assertEquals(true, CompletionSanitizer.isCommentHole("foo(); /*\n"))
        assertEquals(true, CompletionSanitizer.isCommentHole("/**\n * text\n"))
    }

    @Test
    fun stripsCurrentLineIndentEcho() {
        val prefix = "fun foo() {\n    "
        val raw = "    println(1)"

        assertEquals("println(1)", CompletionSanitizer.sanitize(raw, prefix = prefix))
    }

    @Test
    fun keepsExtraNestedIndent() {
        val prefix = "fun foo() {\n    "
        val raw = "        if (ready) {"

        assertEquals("    if (ready) {", CompletionSanitizer.sanitize(raw, prefix = prefix))
    }

    @Test
    fun doesNotStripMultiplyStarInCode() {
        val prefix = "val x =\n    "

        assertEquals("* 2", CompletionSanitizer.sanitize("* 2", prefix = prefix))
    }

    @Test
    fun stripsRepeatedReturnOnCurrentLine() {
        val prefix = "fun add(a: Int, b: Int): Int {\n    return "

        assertEquals("a + b", CompletionSanitizer.sanitize("return a + b", prefix = prefix))
    }

    @Test
    fun stripsPartialIdentifierOverlap() {
        val prefix = "    ret"

        assertEquals("urn 1", CompletionSanitizer.sanitize("return 1", prefix = prefix))
    }

    @Test
    fun doesNotStripSingleLetterBeforeNextIdentifier() {
        val prefix = "val a = "

        assertEquals("add(1)", CompletionSanitizer.sanitize("add(1)", prefix = prefix))
    }

    @Test
    fun dropsWholeLineEchoAfterTrim() {
        assertEquals("", CompletionSanitizer.sanitize("foo(); //", prefix = "    foo(); // "))
    }

    @Test
    fun dropsRepeatedAssignmentEquals() {
        assertEquals("\"Alice\"", CompletionSanitizer.sanitize("= \"Alice\"", prefix = "val name = "))
    }

    @Test
    fun dropsRepeatedUrlScheme() {
        assertEquals("example.com\"", CompletionSanitizer.sanitize("://example.com\"", prefix = "val url = \"http://"))
    }

    @Test
    fun keepsQuotedOneLineStringInsert() {
        val prefix = "val name = "

        assertEquals("\"hello world\"", CompletionSanitizer.sanitize("\"hello world\"", prefix = prefix))
    }

    @Test
    fun unwrapsQuotedMultilineChatAnswer() {
        val raw = "\"fun foo() {\n    return 1\n}\""

        assertEquals("fun foo() {\n    return 1\n}", CompletionSanitizer.sanitize(raw))
    }

    @Test
    fun keepsSameLineOperatorSuffix() {
        val prefix = "val x = "
        val suffix = " + y\n"
        val raw = "1 + y"

        assertEquals("1 + y", CompletionSanitizer.sanitize(raw, prefix = prefix, suffix = suffix))
    }

    @Test
    fun keepsRepeatedStringSyllable() {
        assertEquals("ha", CompletionSanitizer.sanitize("ha", prefix = "const laugh = \"ha"))
    }

    @Test
    fun keepsNestedCallClosers() {
        assertEquals("inner(nested())", CompletionSanitizer.sanitize("inner(nested())", prefix = "outer(wrapper(", suffix = "))"))
    }

    @Test
    fun cutsGarbageAfterFimToken() {
        assertEquals("1", CompletionSanitizer.sanitize("1<|fim_middle|>garbage", prefix = "val n = "))
    }

    @Test
    fun keepsTripleQuotedString() {
        val raw = "\"\"\"\nhello\n\"\"\""
        assertEquals(raw, CompletionSanitizer.sanitize(raw, prefix = "val s = "))
    }

    @Test
    fun keepsMarkdownEmphasisInJavadoc() {
        assertEquals("**Important**", CompletionSanitizer.sanitize("**Important**", prefix = "/**\n * "))
    }

    @Test
    fun doesNotStripBlockMarkerInsideString() {
        assertEquals("/*.ts", CompletionSanitizer.sanitize("/*.ts", prefix = "const glob = \"/*"))
    }

    @Test
    fun keepsFencedMarkdownWhenPrefixIsMarkup() {
        val raw = "```sh\nnpm install\n```"
        assertEquals(raw, CompletionSanitizer.sanitize(raw, prefix = "# Example\n\n"))
    }

    @Test
    fun keepsXmlSampleTag() {
        assertEquals("<code_sample>x</code_sample>", CompletionSanitizer.sanitize("<code_sample>x</code_sample>", prefix = "<root>"))
    }

    @Test
    fun streamingHoldsPartialStopToken() {
        val sanitizer = StreamingCompletionSanitizer(
            prefix = "fun f() {\n    ",
            suffix = "\n}",
            stop = emptyList(),
            holdChars = 4,
        )

        assertEquals("x", sanitizer.push("x<CUR"))
        assertEquals("", sanitizer.push("SOR>more"))
        assertEquals("", sanitizer.finish())
    }

    @Test
    fun streamingStripsRedundantJavadocStar() {
        val sanitizer = StreamingCompletionSanitizer(
            prefix = "/**\n * ",
            suffix = "\n */",
            stop = emptyList(),
            holdChars = 4,
        )

        assertEquals("Ak", sanitizer.push("* Ak"))
        assertEquals("tiviert", sanitizer.push("tiviert"))
        assertEquals("", sanitizer.finish())
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
