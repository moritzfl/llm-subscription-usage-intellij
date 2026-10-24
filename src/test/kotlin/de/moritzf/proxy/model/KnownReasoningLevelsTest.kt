package de.moritzf.proxy.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KnownReasoningLevelsTest {
    private fun levels(id: String) = KnownReasoningLevels.forModel(id)

    @Test
    fun matchesOpenAiFamiliesAsOpenRouterAndLiteLlmList() {
        assertEquals(listOf("none", "low", "medium", "high", "xhigh", "max"), levels("gpt-6-sol"))
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), levels("gpt-6-astra"))
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), levels("gpt-6.1-sol"))
        assertEquals(
            listOf("none", "low", "medium", "high", "xhigh", "max"),
            levels("gpt-5.6-terra"),
        )
        assertEquals(listOf("none", "low", "medium", "high", "xhigh"), levels("gpt-5.4-mini"))
        assertEquals(listOf("medium", "high", "xhigh"), levels("gpt-5.5-pro"))
        assertEquals(listOf("minimal", "low", "medium", "high"), levels("gpt-5-mini"))
        assertEquals(listOf("low", "medium", "high"), levels("gpt-5.1-codex-mini"))
        assertEquals(listOf("low", "medium", "high"), levels("o4-mini"))
        assertEquals(
            listOf("none", "low", "medium", "high", "xhigh", "max"),
            levels("gpt-reserve"),
        )
        assertNull(levels("gpt-4.1"))
    }

    @Test
    fun matchesDeploymentAndTagStyleNames() {
        // Azure deployment names and Ollama tags.
        assertEquals(levels("gpt-5.4-mini"), levels("GPT-5.4-mini"))
        assertEquals(listOf("low", "medium", "high"), levels("gpt-oss:120b"))
        assertEquals(listOf("none", "low", "high", "max"), levels("deepseek-v4-pro:0813"))
        assertEquals(listOf("none", "low", "high", "max"), levels("deepseek-v4.1-flash:1017"))
        assertEquals(listOf("none", "high", "xhigh"), levels("deepseek-v4-flash"))
        assertEquals(listOf("low", "high", "max"), levels("zai-org/GLM-5.3"))
        assertNull(levels("Mistral-Large-3"))
    }

    @Test
    fun matchesOtherFamilies() {
        assertEquals(listOf("low", "medium", "high", "xhigh"), levels("grok-4.6"))
        assertEquals(listOf("none", "low", "medium", "high"), levels("grok-4.3"))
        assertNull(levels("grok-4.20"))
        assertEquals(listOf("low", "medium", "high", "max"), levels("claude-sonnet-4-6"))
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), levels("claude-haiku-5-5"))
        assertNull(levels("claude-sonnet-4.5"))
        assertEquals(listOf("low", "medium", "high"), levels("gemini-3.8-flash"))
        assertEquals(listOf("minimal", "low", "medium", "high"), levels("gemini-3.5-flash-lite"))
        assertEquals(listOf("none", "low", "high", "max"), levels("kimi-k3.1-pro"))
        assertEquals(emptyList(), levels("kimi-k2.7-code"))
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), levels("minimax-m3.1-flash"))
        assertEquals(listOf("none", "high"), levels("mistral-medium-3-5"))
    }
}
