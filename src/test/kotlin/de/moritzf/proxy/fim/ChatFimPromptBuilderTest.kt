package de.moritzf.proxy.fim

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatFimPromptBuilderTest {
    @Test
    fun placesPrefixAndSuffixAroundCursor() {
        val prompt = ChatFimPromptBuilder.userPrompt(
            FimContext(
                schema = FimSchema.UNKNOWN_CHAT,
                prefix = "fun add(a: Int, b: Int): Int {\n    return ",
                suffix = "\n}",
                filePath = "src/Main.kt",
                languageHint = "kt",
            ),
        )

        assertTrue(prompt.contains("File: src/Main.kt"))
        assertTrue(prompt.contains("Language: kt"))
        assertTrue(prompt.contains("<code_before_cursor>\nfun add(a: Int, b: Int): Int {\n    return \n</code_before_cursor>"))
        assertTrue(prompt.contains("<CURSOR>"))
        assertTrue(prompt.contains("<code_after_cursor>\n\n}\n</code_after_cursor>"))
        assertTrue(prompt.contains("Cursor preview (│ is the cursor, do not emit │): "))
        assertTrue(prompt.contains("return │\\n}"))
        assertTrue(ChatFimPromptBuilder.SYSTEM_PROMPT.contains("never repeat a prefix of it"))
        assertFalse(prompt.contains("<|fim_prefix|>"))
        assertFalse(prompt.contains("inside a comment"))
    }

    @Test
    fun doesNotAskToCloseWhenSuffixAlreadyHasCloser() {
        val prompt = ChatFimPromptBuilder.userPrompt(
            FimContext(
                schema = FimSchema.QWEN,
                prefix = "/**\n * Returns ",
                suffix = "\n */\nfun add() {}",
                filePath = "src/Main.kt",
                languageHint = "kt",
            ),
        )

        assertTrue(prompt.contains("already contains `*/`"))
        assertTrue(prompt.contains("do not close it"))
        assertFalse(prompt.contains("closing it is fine"))
        assertTrue(ChatFimPromptBuilder.SYSTEM_PROMPT.contains("KDoc"))
        assertTrue(ChatFimPromptBuilder.SYSTEM_PROMPT.contains("do not repeat it"))
    }

    @Test
    fun forbidsClosingWhenCommentContinuesAfterCursor() {
        val prompt = ChatFimPromptBuilder.userPrompt(
            FimContext(
                schema = FimSchema.QWEN,
                prefix = "/**\n * Returns ",
                suffix = "\n * the sum of a and b.\n */\nfun add() {}",
                filePath = "src/Main.kt",
                languageHint = "kt",
            ),
        )

        assertTrue(prompt.contains("More comment follows after the cursor"))
        assertTrue(prompt.contains("do not close it"))
        assertFalse(prompt.contains("closing it is fine"))
    }

    @Test
    fun treatsTrailingLineCommentAsCommentHole() {
        val prompt = ChatFimPromptBuilder.userPrompt(
            FimContext(
                schema = FimSchema.QWEN,
                prefix = "    foo(); // ",
                suffix = "\n    bar()",
                filePath = "src/Main.kt",
                languageHint = "kt",
            ),
        )

        assertTrue(prompt.contains("inside a line comment"))
    }

    @Test
    fun doesNotTreatUrlSchemeAsCommentHole() {
        val prompt = ChatFimPromptBuilder.userPrompt(
            FimContext(
                schema = FimSchema.QWEN,
                prefix = "val url = \"http://",
                suffix = "\"\n",
                filePath = "src/Main.kt",
                languageHint = "kt",
            ),
        )

        assertFalse(prompt.contains("inside a comment"))
        assertFalse(prompt.contains("The comment ends after the cursor"))
        assertFalse(prompt.contains("More comment follows after the cursor"))
    }

    @Test
    fun includesBudgetedExtraFiles() {
        val prompt = ChatFimPromptBuilder.userPrompt(
            FimContext(
                schema = FimSchema.QWEN,
                prefix = "a",
                suffix = "b",
                extraFiles = listOf(FimFileSlice("src/Util.kt", "fun util() = 1")),
            ),
        )

        assertTrue(prompt.contains("<extra_file path=\"src/Util.kt\">"))
        assertTrue(prompt.contains("fun util() = 1"))
    }

    @Test
    fun omitsDroppedExtraFiles() {
        val prompt = ChatFimPromptBuilder.userPrompt(
            FimContext(
                schema = FimSchema.QWEN,
                prefix = "a",
                suffix = "b",
                extraFiles = emptyList(),
            ),
        )

        assertFalse(prompt.contains("<extra_file"))
    }
}
