package de.moritzf.proxy.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModelAliasResolverTest {
    private val resolver = ModelAliasResolver()

    @Test
    fun parsesReasoningTierSuffixIntoBaseModelAndEffort() {
        val resolved = resolver.resolve("gpt-5.5 (xhigh)")
        assertEquals("gpt-5.5", resolved.model)
        assertEquals("xhigh", resolved.reasoningEffort)
    }

    @Test
    fun parsesSuffixCaseInsensitivelyAndTrimsWhitespace() {
        val resolved = resolver.resolve("gpt-5.4-mini  (High)")
        assertEquals("gpt-5.4-mini", resolved.model)
        assertEquals("high", resolved.reasoningEffort)
    }

    @Test
    fun leavesBareModelUntouched() {
        val resolved = resolver.resolve("gpt-5.5")
        assertEquals("gpt-5.5", resolved.model)
        assertNull(resolved.reasoningEffort)
    }

    @Test
    fun keepsXHighForCurrentModelFamilies() {
        assertEquals("xhigh", resolver.clampReasoningEffort("gpt-5.5", "xhigh"))
        assertEquals("xhigh", resolver.clampReasoningEffort("gpt-5.4", "xhigh"))
        assertEquals("xhigh", resolver.clampReasoningEffort("gpt-5.3-codex-spark", "xhigh"))
    }

    @Test
    fun downgradesXHighForModelsThatDoNotSupportIt() {
        // Models outside the supported families fall back to 'high'.
        assertEquals("high", resolver.clampReasoningEffort("gpt-4o", "xhigh"))
    }

    @Test
    fun mapsMinimalToLow() {
        // The Codex backend rejects 'minimal'; the proxy maps it to the nearest accepted tier.
        assertEquals("low", resolver.clampReasoningEffort("gpt-5.5", "minimal"))
    }

    @Test
    fun clampsCodexMiniToEndpointSupportedEfforts() {
        assertEquals("medium", resolver.clampReasoningEffort("codex-mini", "medium"))
        assertEquals("high", resolver.clampReasoningEffort("codex-mini", "high"))
        assertEquals("medium", resolver.clampReasoningEffort("codex-mini", "low"))
        assertEquals("high", resolver.clampReasoningEffort("codex-mini", "xhigh"))
        assertEquals("medium", resolver.clampReasoningEffort("codex-mini", "minimal"))
        assertEquals("medium", resolver.clampReasoningEffort("codex-mini", "none"))
        assertEquals("medium", resolver.clampReasoningEffort("gpt-5.4-mini", "low"))
        assertEquals("high", resolver.clampReasoningEffort("gpt-5.4-mini", "xhigh"))
    }
}
