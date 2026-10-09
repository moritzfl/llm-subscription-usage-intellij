package de.moritzf.proxy.fim

import kotlin.test.Test
import kotlin.test.assertEquals

class NativeCompletionSanitizerTest {
    @Test
    fun rejectsEchoedIdentifierContinuationButPreservesMissingMiddleText() {
        assertEquals(
            "",
            CompletionSanitizer.sanitizeNative(
                "Name || user.last",
                "const fullName = user.first",
                "Name + \" \" + user.lastName;\n",
                emptyList(),
            ),
        )
        assertEquals(
            "",
            CompletionSanitizer.sanitizeNative("Name", "user.first", "Name", emptyList()),
        )
        assertEquals(
            "rst",
            CompletionSanitizer.sanitizeNative("rst", "user.fi", "Name", emptyList()),
        )
        assertEquals(
            "NameSpace",
            CompletionSanitizer.sanitizeNative("NameSpace", "user.first", "Name", emptyList()),
        )
        assertEquals(
            "Name",
            CompletionSanitizer.sanitizeNative("Name", "user.", "Name", emptyList()),
        )
    }

    @Test
    fun dropsRecordedIdentifierSuffixReplayWithoutTrimmingShortAmbiguousClosers() {
        assertEquals(
            "",
            CompletionSanitizer.sanitizeNative(
                "Name + \" \" + user.last",
                "const fullName = user.first",
                "Name + \" \" + user.lastName;\n",
                emptyList(),
            ),
        )
        assertEquals(
            "foo()",
            CompletionSanitizer.sanitizeNative("foo()", "print(", ")", emptyList()),
        )
        assertEquals(
            "sum",
            CompletionSanitizer.sanitizeNative("sum", "return ", "sum", emptyList()),
        )
    }

    @Test
    fun cutsDeepSeekEndOfSentenceRatherThanLeakingControlTokens() {
        assertEquals(
            "a + b",
            CompletionSanitizer.sanitizeNative(
                "a + b<｜end▁of▁sentence｜>ignored",
                "return ",
                "\n}",
                emptyList(),
            ),
        )
    }

    @Test
    fun removesSuffixReplayAndFimTokens() {
        for (raw in listOf("a + b\n}", "a + b\n}\n", "a + b\n}<｜fim▁end｜>ignored")) {
            assertEquals(
                "a + b",
                CompletionSanitizer.sanitizeNative(raw, "return ", "\n}\n", emptyList()),
            )
        }
    }

    @Test
    fun preservesRealNestedClosersAndMultilineInsertions() {
        assertEquals(
            "a + b\n        }",
            CompletionSanitizer.sanitizeNative(
                "a + b\n        }\n    }",
                "if (a > 0) {\n        return ",
                "\n    }\n}",
                emptyList(),
            ),
        )
        assertEquals(
            "foo()",
            CompletionSanitizer.sanitizeNative("foo()", "print(", ")", emptyList()),
        )
        assertEquals(
            "first()\nsecond()",
            CompletionSanitizer.sanitizeNative("first()\nsecond()", "", "\n}", emptyList()),
        )
    }

    @Test
    fun preservesNativeSourceRatherThanApplyingChatAnswerHeuristics() {
        for (raw in
            listOf("\"hello\"", "```kotlin\nval x = 1\n```", "sure, this is documentation")) {
            assertEquals(raw, CompletionSanitizer.sanitizeNative(raw, "", "", emptyList()))
        }
    }

    @Test
    fun preservesBlankLinesWhenTheRequestHasNoBlankLineStop() {
        val text = "first paragraph\n\n\nsecond paragraph"
        assertEquals(text, CompletionSanitizer.sanitizeNative(text, "/* ", " */", emptyList()))
        assertEquals(
            "first paragraph",
            CompletionSanitizer.sanitizeNative(text, "", "", listOf("\n\n\n")),
        )
    }

    @Test
    fun enforcesAllStopsAndRemovesEchoedCurrentLineAndIndentation() {
        assertEquals(
            "a + b",
            CompletionSanitizer.sanitizeNative(
                "return a + bENDignored",
                "return ",
                "\n}",
                listOf("END"),
            ),
        )
        assertEquals(
            "return a + b",
            CompletionSanitizer.sanitizeNative("    return a + b", "    ", "\n}", emptyList()),
        )
        assertEquals(
            "",
            CompletionSanitizer.sanitizeNative("<｜fim▁end｜>", "return ", "\n}", emptyList()),
        )
    }
}
