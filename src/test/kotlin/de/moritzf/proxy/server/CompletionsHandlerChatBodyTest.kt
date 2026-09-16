package de.moritzf.proxy.server

import de.moritzf.proxy.fim.ChatFimPromptBuilder
import de.moritzf.proxy.fim.CompletionsConfig
import de.moritzf.proxy.fim.CompletionsRequest
import de.moritzf.proxy.fim.FimContext
import de.moritzf.proxy.fim.FimPromptParser
import de.moritzf.proxy.fim.FimSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class CompletionsHandlerChatBodyTest {
    @Test
    fun includesServiceTierOnlyWhenPriorityRequested() {
        val fim = FimContext(schema = FimSchema.QWEN, prefix = "fun add() {\n    ", suffix = "\n}")
        val request = CompletionsRequest(model = CompletionsConfig.FIM_ALIAS_ID, prompt = "x")
        val off = CompletionsHandler.chatBody("sg-grok-4.6", request, fim, 64, 0.1, priorityTier = false, reasoningEffort = "low")
        val on = CompletionsHandler.chatBody("sg-grok-4.6", request, fim, 64, 0.1, priorityTier = true, reasoningEffort = "low")
        val mistral = CompletionsHandler.chatBody("mi-mistral-small-latest", request, fim, 64, 0.1)

        assertNull(off["service_tier"])
        assertEquals(CompletionsConfig.SERVICE_TIER_PRIORITY, on["service_tier"]?.jsonPrimitive?.contentOrNull)
        assertEquals("low", (on["reasoning_effort"] as JsonPrimitive).content)
        assertNull(mistral["reasoning_effort"])
        assertEquals(ChatFimPromptBuilder.PROMPT_CACHE_KEY, off["prompt_cache_key"]?.jsonPrimitive?.contentOrNull)
        assertEquals(2, (off["messages"] as JsonArray).size)
    }

    @Test
    fun nativeBodyUsesParsedPrefixAndSuffixNotTokenSoup() {
        val prompt = "<|fim_suffix|>\n}\n<|fim_prefix|>fun add(a: Int, b: Int): Int {\n    return <|fim_middle|>"
        val fim = FimPromptParser.parse(prompt, null)
        val request = CompletionsRequest(model = CompletionsConfig.FIM_ALIAS_ID, prompt = prompt)
        val body = CompletionsHandler.nativeBody("mi-codestral-latest", request, fim, 48)

        assertEquals("fun add(a: Int, b: Int): Int {\n    return ", body["prompt"]?.jsonPrimitive?.contentOrNull)
        assertEquals("\n}\n", body["suffix"]?.jsonPrimitive?.contentOrNull)
        assertEquals(false, body["prompt"]?.jsonPrimitive?.contentOrNull?.contains("<|fim_"))
    }

    @Test
    fun chatMessageContentIgnoresNonJsonBody() {
        assertEquals("", CompletionsHandler.chatMessageContent("<html>error</html>"))
    }

    @Test
    fun parseTreatsJsonNullPromptAsEmpty() {
        val body = buildJsonObject {
            put("model", JsonPrimitive("qwen2.5-coder"))
            put("prompt", JsonNull)
        }
        assertEquals("", CompletionsRequest.parse(body).prompt)
    }
}
