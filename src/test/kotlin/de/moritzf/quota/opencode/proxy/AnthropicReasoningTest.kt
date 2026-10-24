package de.moritzf.quota.opencode.proxy

import de.moritzf.proxy.server.JsonHelper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AnthropicReasoningTest {
    @Test
    fun claudeUsesOutputConfigEffortOnly() {
        val high = AnthropicReasoning.apply(body("high"), "claude-haiku-5-5")
        assertEquals("high", high["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertTrue("reasoning_effort" !in high)
        assertTrue("thinking" !in high)

        // Anthropic cannot disable thinking everywhere; the lowest effort is the floor.
        val none = AnthropicReasoning.apply(body("none"), "claude-haiku-5-5")
        assertEquals("low", none["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertTrue("thinking" !in none)

        val xhigh = AnthropicReasoning.apply(body("xhigh"), "claude-opus-5-5")
        assertEquals("xhigh", xhigh["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun claudeEffortFollowsTheModelVersion() {
        // Effort arrived with 4.6; Sonnet 4.6 has no xhigh, so it rounds up to max.
        val sonnet46 = AnthropicReasoning.apply(body("xhigh"), "claude-sonnet-4.6")
        assertEquals(
            "max",
            sonnet46["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content,
        )
        val sonnet46Max = AnthropicReasoning.apply(body("max"), "claude-sonnet-4.6")
        assertEquals(
            "max",
            sonnet46Max["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content,
        )
        val opus47 = AnthropicReasoning.apply(body("xhigh"), "claude-opus-4.7")
        assertEquals(
            "xhigh",
            opus47["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content,
        )
        // Older Claude models keep no effort; forwarding would be rejected.
        for (model in listOf("claude-sonnet-4.5", "claude-3.7-sonnet")) {
            val older = AnthropicReasoning.apply(body("high"), model)
            assertTrue("reasoning_effort" !in older, model)
            assertTrue("output_config" !in older, model)
        }
    }

    @Test
    fun qwenIsToldToStopThinkingOnNone() {
        val none = AnthropicReasoning.apply(body("none"), "qwen3.8-flash")
        assertEquals("disabled", none["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)

        val high = AnthropicReasoning.apply(body("high"), "qwen3.8-flash")
        assertEquals("high", high["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertTrue("thinking" !in high, "Qwen thinks by default")
    }

    @Test
    fun minimaxNeedsAdaptiveThinking() {
        val high = AnthropicReasoning.apply(body("xhigh"), "minimax-m3")
        assertEquals("xhigh", high["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals(
            "adaptive",
            high["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content,
            "MiniMax ignores effort alone",
        )

        val none = AnthropicReasoning.apply(body("none"), "minimax-m3")
        assertEquals("disabled", none["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun keepsOtherOutputConfigKeysAndIgnoresUnknownValues() {
        val withKeys = parse("""{"reasoning_effort":"medium","output_config":{"format":"x"}}""")
        val adapted = AnthropicReasoning.apply(withKeys, "claude-haiku-5-5")
        assertEquals(
            "medium",
            adapted["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content,
        )
        assertEquals("x", adapted["output_config"]!!.jsonObject["format"]!!.jsonPrimitive.content)

        val unknown = parse("""{"reasoning_effort":"turbo"}""")
        assertEquals(unknown, AnthropicReasoning.apply(unknown, "claude-haiku-5-5"))
    }

    private fun body(effort: String): JsonObject = parse("""{"reasoning_effort":"$effort"}""")

    private fun parse(json: String): JsonObject =
        JsonHelper.parseToJsonElementOrNull(json) as JsonObject
}
