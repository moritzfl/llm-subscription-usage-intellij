package de.moritzf.proxy.model

import de.moritzf.proxy.server.JsonHelper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject

class ReasoningCapabilitiesTest {
    @Test
    fun readsLiteLlmFlagsWithItsPolarity() {
        // LiteLLM's gpt-5 and gpt-6-astra entries.
        assertEquals(
            listOf("minimal", "low", "medium", "high"),
            ReasoningCapabilities.fromLiteLlm(
                parse(
                    """{"supports_reasoning":true,"supports_none_reasoning_effort":false,"supports_minimal_reasoning_effort":true,"supports_xhigh_reasoning_effort":false}"""
                )
            ),
        )
        assertEquals(
            listOf("low", "medium", "high", "xhigh", "max"),
            ReasoningCapabilities.fromLiteLlm(
                parse(
                    """{"supports_none_reasoning_effort":false,"supports_minimal_reasoning_effort":false,"supports_xhigh_reasoning_effort":true,"supports_max_reasoning_effort":true}"""
                )
            ),
        )
    }

    @Test
    fun declaredLiteLlmLevelsWinWhole() {
        assertEquals(
            listOf("low", "high", "max"),
            ReasoningCapabilities.fromLiteLlm(
                parse(
                    """{"reasoning_effort_levels":["max","low","high"],"supports_xhigh_reasoning_effort":true}"""
                )
            ),
        )
        assertEquals(
            emptyList(),
            ReasoningCapabilities.fromLiteLlm(parse("""{"supports_reasoning":false}""")),
        )
        assertNull(ReasoningCapabilities.fromLiteLlm(parse("""{"supports_reasoning":true}""")))
    }

    @Test
    fun readsOpenRouterModelEntries() {
        val grok =
            parse(
                """{"id":"x-ai/grok-4.6","supported_parameters":["reasoning","reasoning_effort"],"reasoning":{"mandatory":true,"supported_efforts":["xhigh","high","medium","low"],"default_effort":"high"}}"""
            )
        assertEquals(
            listOf("low", "medium", "high", "xhigh"),
            ReasoningCapabilities.fromOpenRouter(grok),
        )
        val noReasoning =
            parse("""{"id":"openai/gpt-4.1","supported_parameters":["tools","temperature"]}""")
        assertEquals(emptyList(), ReasoningCapabilities.fromOpenRouter(noReasoning))
        assertNull(ReasoningCapabilities.fromOpenRouter(parse("""{"id":"x"}""")))
    }

    @Test
    fun writesBothConventionsConsistently() {
        val levels = listOf("high", "none", "low", "medium")
        val liteLlm = ReasoningCapabilities.liteLlmFields(levels)
        assertEquals(true, liteLlm["supports_reasoning"])
        assertEquals(listOf("none", "low", "medium", "high"), liteLlm["reasoning_effort_levels"])
        assertEquals(true, liteLlm["supports_none_reasoning_effort"])
        assertEquals(false, liteLlm["supports_xhigh_reasoning_effort"])
        assertEquals(
            mapOf("supports_reasoning" to false),
            ReasoningCapabilities.liteLlmFields(emptyList()),
        )

        val openRouter = ReasoningCapabilities.openRouterField(listOf("low", "xhigh"))!!
        assertEquals(true, openRouter["mandatory"])
        assertEquals(listOf("xhigh", "low"), openRouter["supported_efforts"])
        assertNull(ReasoningCapabilities.openRouterField(emptyList()))
    }

    private fun parse(json: String): JsonObject =
        JsonHelper.parseToJsonElementOrNull(json) as JsonObject
}
