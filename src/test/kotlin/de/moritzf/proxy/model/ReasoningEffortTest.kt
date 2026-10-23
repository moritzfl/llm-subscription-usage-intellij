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
        assertEquals("none", ReasoningEffort.nearest("low", onOff), "ties go to the lower level")
        assertEquals("high", ReasoningEffort.nearest("medium", onOff))
    }

    @Test
    fun ignoresValuesOutsideTheScale() {
        assertNull(ReasoningEffort.nearest("turbo", listOf("low", "high")))
        assertNull(ReasoningEffort.nearest(null, listOf("low", "high")))
        assertNull(ReasoningEffort.nearest("low", emptyList()))
    }
}
