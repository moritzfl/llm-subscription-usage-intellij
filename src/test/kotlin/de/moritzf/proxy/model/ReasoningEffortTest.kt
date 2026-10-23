package de.moritzf.proxy.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReasoningEffortTest {
    @Test
    fun picksTheClosestSupportedLevel() {
        val gemini = listOf("low", "medium", "high")
        assertEquals("low", ReasoningEffort.nearest("none", gemini))
        assertEquals("low", ReasoningEffort.nearest("minimal", gemini))
        assertEquals("medium", ReasoningEffort.nearest("Medium", gemini))
        assertEquals("high", ReasoningEffort.nearest("xhigh", gemini))
        assertEquals("high", ReasoningEffort.nearest("max", gemini))

        val onOff = listOf("none", "high")
        assertEquals("high", ReasoningEffort.nearest("low", onOff), "keep reasoning on a tie")
        assertEquals("high", ReasoningEffort.nearest("medium", onOff))
        assertEquals("none", ReasoningEffort.nearest("none", onOff))
    }

    @Test
    fun ignoresValuesOutsideTheScale() {
        assertNull(ReasoningEffort.nearest("turbo", listOf("low", "high")))
        assertNull(ReasoningEffort.nearest(null, listOf("low", "high")))
        assertNull(ReasoningEffort.nearest("low", emptyList()))
    }
}
