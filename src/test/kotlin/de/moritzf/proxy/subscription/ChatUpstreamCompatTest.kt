package de.moritzf.proxy.subscription

import de.moritzf.proxy.server.JsonHelper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ChatUpstreamCompatTest {
    @Test
    fun omitsFieldsThatBreakJunieOnCopilotChat() {
        val body = JsonHelper.parseToJsonElementOrNull(
            """{"stop":["</COMMAND>"],"reasoning_effort":"low","messages":[]}""",
        )!!.jsonObject

        val mini = ChatUpstreamCompat.omitUnsupportedChatFields("gpt-5-mini", body)
        assertFalse("stop" in mini)
        assertTrue("reasoning_effort" in mini)

        val gemini = ChatUpstreamCompat.omitUnsupportedChatFields("gemini-3.8-flash", body)
        assertTrue("stop" in gemini)
        assertFalse("reasoning_effort" in gemini)

        val kimi = ChatUpstreamCompat.omitUnsupportedChatFields("kimi-k2.7-code", body)
        assertFalse("stop" in kimi)
        assertFalse("reasoning_effort" in kimi)

        val sol = ChatUpstreamCompat.omitUnsupportedChatFields("gpt-6-sol", body)
        assertEquals(body.keys, sol.keys)
    }

    @Test
    fun addsGeminiToolReasoningHeadroom() {
        val body = JsonHelper.parseToJsonElementOrNull(
            """{"max_tokens":64,"tools":[{"type":"function"}],"messages":[]}""",
        )!!.jsonObject
        val adapted = ChatUpstreamCompat.adaptChat("gemini-3.8-flash", body)
        assertEquals("2112", adapted["max_tokens"]!!.jsonPrimitive.content)
        val plain = JsonHelper.parseToJsonElementOrNull("""{"max_tokens":64,"messages":[]}""")!!.jsonObject
        val noTools = ChatUpstreamCompat.adaptChat("gemini-3.8-flash", plain)
        assertEquals("64", noTools["max_tokens"]!!.jsonPrimitive.content)
    }

    @Test
    fun emulatesDroppedStopSequenceForJunie() {
        val request = JsonHelper.parseToJsonElementOrNull(
            """{"stop":["</COMMAND>"]}""",
        ) as JsonObject
        val raw = """{"choices":[{"message":{"role":"assistant","content":"before</COMMAND> after"},"finish_reason":"stop"}]}"""
        val adapted = JsonHelper.parseToJsonElementOrNull(ChatUpstreamCompat.applyStop(raw, request))!!.jsonObject
        val first = (adapted["choices"] as kotlinx.serialization.json.JsonArray)[0].jsonObject
        assertEquals("before", first["message"]!!.jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("stop", first["finish_reason"]!!.jsonPrimitive.content)
        assertEquals("</COMMAND>", first["finish_details"]!!.jsonObject["stop"]!!.jsonPrimitive.content)
    }
}
