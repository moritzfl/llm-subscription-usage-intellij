package de.moritzf.proxy.subscription

import de.moritzf.proxy.server.JsonHelper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ReasoningEffortRequestsTest {
    @Test
    fun fitsChatEffortToTheModelsLevels() {
        val body = parse("""{"messages":[],"reasoning_effort":"medium"}""")
        val chat = SubscriptionProxyRoute.CHAT_COMPLETIONS

        assertSame(body, adaptReasoningEffort(chat, null, body), "unknown levels forward as is")
        assertTrue("reasoning_effort" !in adaptReasoningEffort(chat, emptyList(), body))
        assertEquals(
            "high",
            adaptReasoningEffort(chat, listOf("none", "high"), body)["reasoning_effort"]!!
                .jsonPrimitive
                .content,
        )
        val bogus = parse("""{"messages":[],"reasoning_effort":"turbo"}""")
        assertTrue("reasoning_effort" !in adaptReasoningEffort(chat, listOf("high"), bogus))
    }

    @Test
    fun fitsResponsesEffortAndKeepsOtherReasoningOptions() {
        val responses = SubscriptionProxyRoute.RESPONSES
        val body = parse("""{"input":"hi","reasoning":{"effort":"low","summary":"auto"}}""")

        val adapted = adaptReasoningEffort(responses, listOf("none", "high"), body)
        val reasoning = adapted["reasoning"]!!.jsonObject
        assertEquals("high", reasoning["effort"]!!.jsonPrimitive.content)
        assertEquals("auto", reasoning["summary"]!!.jsonPrimitive.content)

        val noneBody = parse("""{"input":"hi","reasoning":{"effort":"none"}}""")
        val noneAdapted = adaptReasoningEffort(responses, listOf("none", "high"), noneBody)
        assertEquals(
            "none",
            noneAdapted["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content,
        )

        val effortOnly = parse("""{"input":"hi","reasoning":{"effort":"low"}}""")
        assertTrue("reasoning" !in adaptReasoningEffort(responses, emptyList(), effortOnly))
    }

    private fun parse(json: String): JsonObject =
        JsonHelper.parseToJsonElementOrNull(json) as JsonObject
}
