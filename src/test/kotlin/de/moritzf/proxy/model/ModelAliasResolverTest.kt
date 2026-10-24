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
        assertEquals("xhigh", resolver.clampReasoningEffort("gpt-6-astra", "xhigh"))
        assertEquals("xhigh", resolver.clampReasoningEffort("gpt-5.6-sol", "xhigh"))
        assertEquals("xhigh", resolver.clampReasoningEffort("gpt-5.5", "xhigh"))
        assertEquals("xhigh", resolver.clampReasoningEffort("gpt-5.4", "xhigh"))
        assertEquals("xhigh", resolver.clampReasoningEffort("gpt-5.2", "xhigh"))
    }

    @Test
    fun keepsMaxAndUltraForGpt56Family() {
        assertEquals("max", resolver.clampReasoningEffort("gpt-6-astra", "max"))
        assertEquals("ultra", resolver.clampReasoningEffort("gpt-6-astra", "ultra"))
        assertEquals("max", resolver.clampReasoningEffort("gpt-reserve", "ultra"))
        assertEquals("xhigh", resolver.clampReasoningEffort("gpt-reserve", "xhigh"))
        assertEquals("ultra", resolver.clampReasoningEffort("gpt-6-sol", "ultra"))
        assertEquals("max", resolver.clampReasoningEffort("gpt-6-luna", "ultra"))
        assertEquals("max", resolver.clampReasoningEffort("gpt-5.6-sol", "max"))
        assertEquals("ultra", resolver.clampReasoningEffort("gpt-5.6-terra", "ultra"))
        assertEquals("max", resolver.clampReasoningEffort("gpt-5.6-luna", "ultra"))
        assertEquals("xhigh", resolver.clampReasoningEffort("gpt-5.5", "max"))
    }

    @Test
    fun parsesMaxAndUltraSuffixes() {
        val max = resolver.resolve("gpt-6-astra (max)")
        assertEquals("gpt-6-astra", max.model)
        assertEquals("max", max.reasoningEffort)
        val ultra = resolver.resolve("gpt-6-astra (ultra)")
        assertEquals("gpt-6-astra", ultra.model)
        assertEquals("ultra", ultra.reasoningEffort)
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

    @Test
    fun appliesChatGptBackendLimitsOnlyToTheChatGptBackend() {
        fun api(model: String, effort: String) =
            resolver.clampReasoningEffort(model, effort, chatGptBackend = false)

        // Mini models and codex slugs accept the API's levels on Copilot, Azure, or OpenCode.
        assertEquals("low", api("gpt-5.4-mini", "low"))
        assertEquals("none", api("gpt-5.4-mini", "none"))
        assertEquals("none", api("gpt-5.3-codex", "none"))
        // gpt-5.1-codex cannot turn reasoning off.
        assertEquals("low", api("gpt-5.1-codex", "none"))
        // `minimal` stays only for the original GPT-5 family, which documents it.
        assertEquals("minimal", api("gpt-5", "minimal"))
        assertEquals("minimal", api("gpt-5-mini", "minimal"))
        assertEquals("minimal", api("gpt-5-nano-2025-08-07", "minimal"))
        assertEquals("low", api("gpt-5.5", "minimal"))
        // A model without reasoning effort sends none.
        assertEquals(null, api("kimi-k2.7-code", "high"))
        // GPT-6 takes none..max; Grok stops at xhigh and has no `none`; Muse has no `none`.
        assertEquals("none", api("gpt-6-luna", "none"))
        assertEquals("max", api("gpt-6-luna", "ultra"))
        assertEquals("low", api("grok-4.6", "none"))
        assertEquals("xhigh", api("grok-4.6", "max"))
        assertEquals("minimal", api("muse-spark-1.3-contributor", "none"))
        // Unknown families keep the conservative mapping.
        assertEquals("high", api("gpt-4o", "xhigh"))
    }
}
