package de.moritzf.proxy.subscription

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class LiteLlmRequestSanitizerTest {
    @Test
    fun leavesBodyUnchangedWhenDropParamsAbsent() {
        val body = parse(
            """{"model":"mi-codestral-latest","messages":[],"user":"primary","seed":100000}""",
        )
        val sanitized = LiteLlmRequestSanitizer.sanitize(SubscriptionProxyRoute.CHAT_COMPLETIONS, body)
        assertEquals(body, sanitized)
    }

    @Test
    fun dropsLiteLlmExtrasWhenDropParamsIsTrue() {
        val body = parse(
            """{"model":"mi-codestral-latest","messages":[{"role":"user","content":"hi"}],""" +
                """"user":"capability_filter","seed":100000,"drop_params":true,""" +
                """"thinking":{"type":"enabled"},"enable_thinking":true,""" +
                """"chat_template_kwargs":{"x":1},"temperature":0.0,"reasoning_effort":"medium"}""",
        )
        val sanitized = LiteLlmRequestSanitizer.sanitize(SubscriptionProxyRoute.CHAT_COMPLETIONS, body)
        assertEquals("mi-codestral-latest", sanitized["model"]!!.jsonPrimitive.content)
        assertTrue("messages" in sanitized)
        assertTrue("temperature" in sanitized)
        assertTrue("reasoning_effort" in sanitized)
        assertFalse("drop_params" in sanitized)
        assertFalse("user" in sanitized)
        assertFalse("seed" in sanitized)
        assertFalse("thinking" in sanitized)
        assertFalse("enable_thinking" in sanitized)
        assertFalse("chat_template_kwargs" in sanitized)
    }

    @Test
    fun onlyStripsDropParamsFlagOnNonChatRoutes() {
        val body = parse(
            """{"model":"codestral-latest","prompt":"fun ","suffix":"}","drop_params":true,"user":"primary"}""",
        )
        val sanitized = LiteLlmRequestSanitizer.sanitize(SubscriptionProxyRoute.FIM_COMPLETIONS, body)
        assertFalse("drop_params" in sanitized)
        assertTrue("user" in sanitized)
        assertTrue("prompt" in sanitized)
        assertTrue("suffix" in sanitized)
    }

    private fun parse(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject
}
