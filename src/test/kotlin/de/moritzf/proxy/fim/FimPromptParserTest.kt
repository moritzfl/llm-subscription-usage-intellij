package de.moritzf.proxy.fim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FimPromptParserTest {
    @Test
    fun parsesQwenCaptureFromLlm20422() {
        val prompt = """
            <|repo_name|>test-java
            <|file_sep|>src/Main.java
            <|fim_prefix|>class Main {
              public static void main(String[] args) {
                System.out.println(
            <|fim_suffix|>
              }
            }
            <|fim_middle|>
        """.trimIndent()

        val parsed = FimPromptParser.parse(prompt, null)

        assertEquals(FimSchema.QWEN, parsed.schema)
        assertEquals("test-java", parsed.repoName)
        assertEquals("src/Main.java", parsed.filePath)
        assertEquals("java", parsed.languageHint)
        assertTrue(parsed.prefix.contains("System.out.println("))
        assertTrue(parsed.suffix.contains("}"))
        assertTrue(parsed.extraFiles.isEmpty())
    }

    @Test
    fun parsesJetbrainsQwenSuffixBeforePrefix() {
        val prompt = "<|fim_suffix|><|fim_prefix|>def add(a,b):\n    <|fim_middle|>"

        val parsed = FimPromptParser.parse(prompt)

        assertEquals(FimSchema.QWEN, parsed.schema)
        assertEquals("def add(a,b):\n    ", parsed.prefix)
        assertEquals("", parsed.suffix)
    }

    @Test
    fun parsesJetbrainsQwenSuffixBeforePrefixWithSuffixBody() {
        val prompt = "<|fim_suffix|>\n}\n<|fim_prefix|>fun add(a: Int, b: Int): Int {\n    return <|fim_middle|>"

        val parsed = FimPromptParser.parse(prompt)

        assertEquals(FimSchema.QWEN, parsed.schema)
        assertEquals("fun add(a: Int, b: Int): Int {\n    return ", parsed.prefix)
        assertEquals("\n}\n", parsed.suffix)
    }

    @Test
    fun parsesDeepSeekAngleTokens() {
        val prompt = "<fim_prefix>fun hello() {\n    <fim_suffix>\n}\n<fim_middle>"

        val parsed = FimPromptParser.parse(prompt)

        assertEquals(FimSchema.DEEPSEEK, parsed.schema)
        assertEquals("fun hello() {\n    ", parsed.prefix)
        assertEquals("\n}\n", parsed.suffix)
    }

    @Test
    fun parsesDeepSeekCjkTokens() {
        val prompt = "<｜fim▁begin｜>abc<｜fim▁hole｜>def<｜fim▁end｜>"

        val parsed = FimPromptParser.parse(prompt)

        assertEquals(FimSchema.DEEPSEEK_CJK, parsed.schema)
        assertEquals("abc", parsed.prefix)
        assertEquals("def", parsed.suffix)
    }

    @Test
    fun usesPromptAndSuffixFieldsWhenNoTokens() {
        val parsed = FimPromptParser.parse("fun add(a: Int, b: Int): Int {\n    return ", "\n}\n")

        assertEquals(FimSchema.UNKNOWN_CHAT, parsed.schema)
        assertEquals("fun add(a: Int, b: Int): Int {\n    return ", parsed.prefix)
        assertEquals("\n}\n", parsed.suffix)
    }

    @Test
    fun treatsUnknownSoupAsPrefix() {
        val parsed = FimPromptParser.parse("just some code", null)

        assertEquals(FimSchema.UNKNOWN_CHAT, parsed.schema)
        assertEquals("just some code", parsed.prefix)
        assertEquals("", parsed.suffix)
    }

    @Test
    fun extractsExtraFileSepHunks() {
        val prompt = """
            <|file_sep|>src/Util.kt
            fun util() = 1
            <|file_sep|>src/Main.kt
            <|fim_prefix|>fun main() {
            <|fim_suffix|>
            }
            <|fim_middle|>
        """.trimIndent()

        val parsed = FimPromptParser.parse(prompt)

        assertEquals("src/Main.kt", parsed.filePath)
        assertEquals("kt", parsed.languageHint)
        assertEquals(1, parsed.extraFiles.size)
        assertEquals("src/Util.kt", parsed.extraFiles[0].path)
        assertTrue(parsed.extraFiles[0].content.contains("fun util()"))
    }

    @Test
    fun parsesCodestralPrefixSuffixMarkers() {
        val parsed = FimPromptParser.parse("[PREFIX]before[SUFFIX]after")

        assertEquals(FimSchema.CODESTRAL, parsed.schema)
        assertEquals("before", parsed.prefix)
        assertEquals("after", parsed.suffix)
    }

    @Test
    fun prefersFimTokensOverSuffixField() {
        val parsed = FimPromptParser.parse("<|fim_prefix|>pre<|fim_suffix|>suf<|fim_middle|>", "ignored")

        assertEquals(FimSchema.QWEN, parsed.schema)
        assertEquals("pre", parsed.prefix)
        assertEquals("suf", parsed.suffix)
    }
}
