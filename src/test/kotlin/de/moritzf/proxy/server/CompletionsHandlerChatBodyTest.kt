package de.moritzf.proxy.server

import de.moritzf.proxy.fim.CompletionsConfig
import de.moritzf.proxy.fim.CompletionsRequest
import de.moritzf.proxy.fim.FimContext
import de.moritzf.proxy.fim.FimSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class CompletionsHandlerChatBodyTest {
    @Test
    fun includesServiceTierOnlyWhenPriorityRequested() {
        val fim = FimContext(schema = FimSchema.QWEN, prefix = "fun add() {\n    ", suffix = "\n}")
        val request = CompletionsRequest(model = CompletionsConfig.FIM_ALIAS_ID, prompt = "x")
        val off = CompletionsHandler.chatBody("sg-grok-4.6", request, fim, 64, 0.1, priorityTier = false)
        val on = CompletionsHandler.chatBody("sg-grok-4.6", request, fim, 64, 0.1, priorityTier = true)

        assertNull(off["service_tier"])
        assertEquals(CompletionsConfig.SERVICE_TIER_PRIORITY, on["service_tier"]?.jsonPrimitive?.contentOrNull)
        assertEquals("low", (on["reasoning_effort"] as JsonPrimitive).content)
    }
}
