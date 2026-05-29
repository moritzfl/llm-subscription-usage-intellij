package de.moritzf.quota.idea.common

import kotlin.test.Test
import kotlin.test.assertEquals

class QuotaProviderTypeTest {
    @Test
    fun defaultProviderOrderIsAlphabetical() {
        assertEquals(
            listOf(
                QuotaProviderType.CURSOR,
                QuotaProviderType.GEMINI,
                QuotaProviderType.KIMI,
                QuotaProviderType.MINIMAX,
                QuotaProviderType.OLLAMA,
                QuotaProviderType.OPEN_AI,
                QuotaProviderType.OPEN_CODE,
                QuotaProviderType.ZAI,
            ),
            QuotaProviderType.defaultProviderOrder(),
        )
    }

    @Test
    fun mergeProviderOrderUsesAlphabeticalDefaultWhenStoredOrderEmpty() {
        assertEquals(QuotaProviderType.defaultProviderOrder(), QuotaProviderType.mergeProviderOrder(emptyList()))
    }

    @Test
    fun mergeProviderOrderInsertsAlphabeticallyFirstProviderAtStart() {
        val merged = QuotaProviderType.mergeProviderOrder(
            listOf(
                QuotaProviderType.GEMINI,
                QuotaProviderType.OPEN_AI,
                QuotaProviderType.OPEN_CODE,
            ),
        )

        assertEquals(QuotaProviderType.CURSOR, merged.first())
        assertEquals(
            listOf(
                QuotaProviderType.CURSOR,
                QuotaProviderType.GEMINI,
                QuotaProviderType.KIMI,
                QuotaProviderType.MINIMAX,
                QuotaProviderType.OLLAMA,
                QuotaProviderType.OPEN_AI,
                QuotaProviderType.OPEN_CODE,
                QuotaProviderType.ZAI,
            ),
            merged,
        )
    }

    @Test
    fun mergeProviderOrderPreservesRelativeCustomOrderForExistingProviders() {
        val customOrder = listOf(
            QuotaProviderType.OPEN_CODE,
            QuotaProviderType.GEMINI,
            QuotaProviderType.OPEN_AI,
        )

        val merged = QuotaProviderType.mergeProviderOrder(customOrder)
        val openCodeIndex = merged.indexOf(QuotaProviderType.OPEN_CODE)
        val geminiIndex = merged.indexOf(QuotaProviderType.GEMINI)
        val openAiIndex = merged.indexOf(QuotaProviderType.OPEN_AI)

        assertEquals(true, openCodeIndex < geminiIndex)
        assertEquals(true, geminiIndex < openAiIndex)
    }

    @Test
    fun mergeProviderOrderInsertsAfterAlphabeticalPredecessorInCustomOrder() {
        val merged = QuotaProviderType.mergeProviderOrder(
            listOf(
                QuotaProviderType.OPEN_CODE,
                QuotaProviderType.GEMINI,
                QuotaProviderType.OPEN_AI,
            ),
        )

        assertEquals(
            listOf(
                QuotaProviderType.CURSOR,
                QuotaProviderType.OPEN_CODE,
                QuotaProviderType.ZAI,
                QuotaProviderType.GEMINI,
                QuotaProviderType.KIMI,
                QuotaProviderType.MINIMAX,
                QuotaProviderType.OLLAMA,
                QuotaProviderType.OPEN_AI,
            ),
            merged,
        )
    }
}
