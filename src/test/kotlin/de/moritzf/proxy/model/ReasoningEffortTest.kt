package de.moritzf.proxy.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReasoningEffortTest {
    @Test
    fun roundsUpToTheWeakestSupportedLevelLikeLiteLlm() {
        val gemini = listOf("low", "medium", "high")
        assertEquals("low", ReasoningEffort.fit("minimal", gemini))
        assertEquals("medium", ReasoningEffort.fit("Medium", gemini))
        assertEquals("high", ReasoningEffort.fit("xhigh", gemini), "strongest when none is enough")

        val glm = listOf("low", "high", "max")
        assertEquals("high", ReasoningEffort.fit("medium", glm))
        assertEquals("max", ReasoningEffort.fit("xhigh", glm))
    }

    @Test
    fun neverRoundsOntoNoneButTurnsNoneIntoTheWeakestLevel() {
        val onOff = listOf("none", "high")
        assertEquals("high", ReasoningEffort.fit("minimal", onOff))
        assertEquals("high", ReasoningEffort.fit("low", onOff))
        assertEquals("none", ReasoningEffort.fit("none", onOff))

        // Grok and GLM-5.3 reject none; the weakest level beats an upstream error.
        assertEquals("low", ReasoningEffort.fit("none", listOf("low", "medium", "high")))
        assertEquals("none", ReasoningEffort.fit("high", listOf("none")))
    }

    @Test
    fun reportsTheLowestLevel() {
        assertEquals("none", ReasoningEffort.lowest(listOf("high", "none")))
        assertEquals("minimal", ReasoningEffort.lowest(listOf("max", "minimal", "low")))
        assertNull(ReasoningEffort.lowest(emptyList()))
    }

    @Test
    fun ignoresValuesOutsideTheScale() {
        assertNull(ReasoningEffort.fit("turbo", listOf("low", "high")))
        assertNull(ReasoningEffort.fit(null, listOf("low", "high")))
        assertNull(ReasoningEffort.fit("low", emptyList()))
    }
}
