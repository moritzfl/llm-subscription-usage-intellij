package de.moritzf.quota.idea.common

import kotlin.test.Test
import kotlin.test.assertEquals

class QuotaProviderTypeTest {
    @Test
    fun defaultProviderOrderIsAlphabetical() {
        assertEquals(
            listOf(
                QuotaProviderType.CLAUDE,
                QuotaProviderType.CURSOR,
                QuotaProviderType.GITHUB,
                QuotaProviderType.KIMI,
                QuotaProviderType.MINIMAX,
                QuotaProviderType.OLLAMA,
                QuotaProviderType.OPEN_AI,
                QuotaProviderType.OPEN_CODE,
                QuotaProviderType.SUPERGROK,
                QuotaProviderType.ZAI,
            ),
            QuotaProviderType.defaultProviderOrder(),
        )
    }

    @Test
    fun registryCoversEveryProviderTypeOnce() {
        assertEquals(
            QuotaProviderType.entries.toSet(),
            QuotaProviderRegistry.all.map { it.type }.toSet(),
        )
        assertEquals(QuotaProviderRegistry.all.size, QuotaProviderRegistry.all.map { it.type }.toSet().size)
    }

    @Test
    fun registryCreatesOneProviderPerRegistration() {
        assertEquals(
            QuotaProviderRegistry.all.map { it.type },
            QuotaProviderRegistry.createProviders().map { it.type },
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
                QuotaProviderType.OPEN_AI,
                QuotaProviderType.OPEN_CODE,
            ),
        )

        assertEquals(QuotaProviderType.CLAUDE, merged.first())
        assertEquals(
            listOf(
                QuotaProviderType.CLAUDE,
                QuotaProviderType.CURSOR,
                QuotaProviderType.GITHUB,
                QuotaProviderType.KIMI,
                QuotaProviderType.MINIMAX,
                QuotaProviderType.OLLAMA,
                QuotaProviderType.OPEN_AI,
                QuotaProviderType.OPEN_CODE,
                QuotaProviderType.SUPERGROK,
                QuotaProviderType.ZAI,
            ),
            merged,
        )
    }

    @Test
    fun mergeProviderOrderPreservesRelativeCustomOrderForExistingProviders() {
        val customOrder = listOf(
            QuotaProviderType.OPEN_CODE,
            QuotaProviderType.KIMI,
            QuotaProviderType.OPEN_AI,
        )

        val merged = QuotaProviderType.mergeProviderOrder(customOrder)
        val openCodeIndex = merged.indexOf(QuotaProviderType.OPEN_CODE)
        val kimiIndex = merged.indexOf(QuotaProviderType.KIMI)
        val openAiIndex = merged.indexOf(QuotaProviderType.OPEN_AI)

        assertEquals(true, openCodeIndex < kimiIndex)
        assertEquals(true, kimiIndex < openAiIndex)
    }

    @Test
    fun mergeProviderOrderInsertsAfterAlphabeticalPredecessorInCustomOrder() {
        val merged = QuotaProviderType.mergeProviderOrder(
            listOf(
                QuotaProviderType.OPEN_CODE,
                QuotaProviderType.KIMI,
                QuotaProviderType.OPEN_AI,
            ),
        )

        assertEquals(
            listOf(
                QuotaProviderType.CLAUDE,
                QuotaProviderType.CURSOR,
                QuotaProviderType.GITHUB,
                QuotaProviderType.OPEN_CODE,
                QuotaProviderType.SUPERGROK,
                QuotaProviderType.ZAI,
                QuotaProviderType.KIMI,
                QuotaProviderType.MINIMAX,
                QuotaProviderType.OLLAMA,
                QuotaProviderType.OPEN_AI,
            ),
            merged,
        )
    }
}
