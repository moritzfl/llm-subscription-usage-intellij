package de.moritzf.quota.opencode.proxy

import de.moritzf.proxy.server.JsonHelper
import de.moritzf.proxy.subscription.SubscriptionProxyModel
import de.moritzf.proxy.subscription.SubscriptionProxyRequest
import de.moritzf.proxy.subscription.SubscriptionProxyRoute
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class GoogleGeminiChatBridgeTest {
    private val bridge = GoogleGeminiChatBridge()

    @Test
    fun mapsChatRequestToGeminiContentsAndTools() {
        val body =
            """
            {
              "model": "oc-zen-gemini-3.8-flash",
              "messages": [
                {"role": "system", "content": "You are Junie"},
                {"role": "user", "content": [
                  {"type": "text", "text": "look"},
                  {"type": "image_url", "image_url": {"url": "data:image/png;base64,AAAA"}}
                ]},
                {"role": "assistant", "content": null, "tool_calls": [
                  {"id": "call_1", "type": "function", "function": {"name": "submit", "arguments": "{\"solution_summary\":\"done\"}"}}
                ]},
                {"role": "tool", "tool_call_id": "call_1", "content": "ok"}
              ],
              "tools": [{"type": "function", "function": {
                "name": "submit",
                "description": "Submit",
                "parameters": {"type": "object", "properties": {"solution_summary": {"type": "string"}}}
              }}],
              "tool_choice": "auto",
              "max_tokens": 64,
              "temperature": 0.2,
              "stop": ["</COMMAND>"],
              "reasoning_effort": "low"
            }
            """
        val output =
            bridge.openAiChatToGoogleBody(
                request(body),
                parse(body),
                buildJsonObject {
                    put(
                        "thinkingConfig",
                        buildJsonObject { put("thinkingLevel", "high") },
                    )
                },
            )

        val contents = output["contents"]!!.jsonArray
        assertEquals(
            listOf("user", "model", "user"),
            contents.map { it.jsonObject["role"]!!.jsonPrimitive.content },
        )
        val userParts = contents[0].jsonObject["parts"]!!.jsonArray
        assertEquals("look", userParts[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(
            "image/png",
            userParts[1].jsonObject["inlineData"]!!.jsonObject["mimeType"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "AAAA",
            userParts[1].jsonObject["inlineData"]!!.jsonObject["data"]!!.jsonPrimitive.content,
        )

        val functionCall = contents[1].jsonObject["parts"]!!.jsonArray[0].jsonObject
        assertEquals(
            "submit",
            functionCall["functionCall"]!!.jsonObject["name"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "done",
            functionCall["functionCall"]!!
                .jsonObject["args"]!!
                .jsonObject["solution_summary"]!!
                .jsonPrimitive
                .content,
        )
        assertEquals(
            "skip_thought_signature_validator",
            functionCall["thoughtSignature"]!!.jsonPrimitive.content,
        )

        val functionResponse = contents[2].jsonObject["parts"]!!.jsonArray[0].jsonObject
        assertEquals(
            "call_1",
            functionResponse["functionResponse"]!!.jsonObject["id"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "ok",
            functionResponse["functionResponse"]!!
                .jsonObject["response"]!!
                .jsonObject["content"]!!
                .jsonPrimitive
                .content,
        )

        val system = output["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray
        assertEquals("You are Junie", system[0].jsonObject["text"]!!.jsonPrimitive.content)
        val declaration =
            output["tools"]!!
                .jsonArray[0]
                .jsonObject["functionDeclarations"]!!
                .jsonArray[0]
                .jsonObject
        assertEquals("submit", declaration["name"]!!.jsonPrimitive.content)
        assertTrue("parametersJsonSchema" in declaration)
        assertEquals(
            "AUTO",
            output["toolConfig"]!!
                .jsonObject["functionCallingConfig"]!!
                .jsonObject["mode"]!!
                .jsonPrimitive
                .content,
        )
        val generation = output["generationConfig"]!!.jsonObject
        assertEquals(64, generation["maxOutputTokens"]!!.jsonPrimitive.content.toInt())
        assertEquals(0.2, generation["temperature"]!!.jsonPrimitive.content.toDouble())
        assertEquals("</COMMAND>", generation["stopSequences"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(
            "high",
            generation["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun mapsGeminiResponseAndReplaysThoughtSignature() {
        val response =
            """
            {
              "candidates": [{
                "content": {"role": "model", "parts": [
                  {"text": "done"},
                  {"functionCall": {"name": "submit", "args": {"solution_summary": "done"}}, "thoughtSignature": "sig-1"}
                ]},
                "finishReason": "STOP"
              }],
              "usageMetadata": {
                "promptTokenCount": 10,
                "candidatesTokenCount": 5,
                "thoughtsTokenCount": 2,
                "totalTokenCount": 17
              }
            }
            """
        val mapped = parse(bridge.googleResponseToOpenAiChat(request("{}"), response))
        val message = mapped["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject
        assertEquals("done", message["content"]!!.jsonPrimitive.content)
        val toolCall = message["tool_calls"]!!.jsonArray[0].jsonObject
        val id = toolCall["id"]!!.jsonPrimitive.content
        assertEquals(
            "tool_calls",
            mapped["choices"]!!.jsonArray[0].jsonObject["finish_reason"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "done",
            toolCall["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content.let {
                parse(it)["solution_summary"]!!.jsonPrimitive.content
            },
        )
        assertEquals(
            10,
            mapped["usage"]!!.jsonObject["prompt_tokens"]!!.jsonPrimitive.content.toInt(),
        )
        assertEquals(
            7,
            mapped["usage"]!!.jsonObject["completion_tokens"]!!.jsonPrimitive.content.toInt(),
        )
        assertEquals(
            17,
            mapped["usage"]!!.jsonObject["total_tokens"]!!.jsonPrimitive.content.toInt(),
        )

        val replay =
            """
            {"messages": [
              {"role": "assistant", "content": null, "tool_calls": [
                {"id": "$id", "type": "function", "function": {"name": "submit", "arguments": "{\"solution_summary\":\"done\"}"}}
              ]},
              {"role": "tool", "tool_call_id": "$id", "content": "ok"}
            ]}
            """
        val replayed =
            bridge.openAiChatToGoogleBody(request(replay), parse(replay), JsonObject(emptyMap()))
        val replayedCall =
            replayed["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray[0].jsonObject
        assertEquals("sig-1", replayedCall["thoughtSignature"]!!.jsonPrimitive.content)
    }

    @Test
    fun streamsOpenAiChunksAndEndsWithDone() {
        val request = request("""{"stream": true}""")
        val first =
            bridge.openAiChatSseLine(
                request,
                """data: {"candidates":[{"content":{"role":"model","parts":[{"text":"hel"}]}}]}""",
            )
        assertTrue(first!!.contains("\"role\":\"assistant\""), first)
        assertTrue(first.contains("\"content\":\"hel\""), first)
        val second =
            bridge.openAiChatSseLine(
                request,
                """data: {"candidates":[{"content":{"parts":[{"text":"lo"}]}}]}""",
            )
        assertTrue(second!!.contains("\"content\":\"lo\""), second)
        val final =
            bridge.openAiChatSseLine(
                request,
                """data: {"candidates":[{"content":{"parts":[{"functionCall":{"name":"submit","args":{"solution_summary":"done"}},"thoughtSignature":"sig-2"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":2}}""",
            )
        assertTrue(final!!.contains("\"finish_reason\":\"tool_calls\""), final)
        val end = bridge.openAiChatSseEnd(request)!!
        assertTrue(end.endsWith("data: [DONE]\n\n"), end)
        val id =
            parse(final.substringAfter("data: ").substringBefore("\n"))["choices"]!!
                .jsonArray[0]
                .jsonObject["delta"]!!
                .jsonObject["tool_calls"]!!
                .jsonArray[0]
                .jsonObject["id"]!!
                .jsonPrimitive
                .content
        val replay =
            """{"messages":[{"role":"assistant","content":null,"tool_calls":[{"id":"$id","type":"function","function":{"name":"submit","arguments":"{}"}}]}]}"""
        val body =
            bridge.openAiChatToGoogleBody(request(replay), parse(replay), JsonObject(emptyMap()))
        assertEquals(
            "sig-2",
            body["contents"]!!
                .jsonArray[0]
                .jsonObject["parts"]!!
                .jsonArray[0]
                .jsonObject["thoughtSignature"]!!
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun retainsTextThoughtSignatureForFollowUpTurns() {
        val response =
            """
            {
              "candidates": [{
                "content": {"role": "model", "parts": [
                  {"text": "The answer is 42."},
                  {"text": "", "thoughtSignature": "text-sig"}
                ]},
                "finishReason": "STOP"
              }]
            }
            """
        val mapped = parse(bridge.googleResponseToOpenAiChat(request("{}"), response))
        val text =
            mapped["choices"]!!
                .jsonArray[0]
                .jsonObject["message"]!!
                .jsonObject["content"]!!
                .jsonPrimitive
                .content
        assertEquals("The answer is 42.", text)
        val followUp =
            """{"messages":[{"role":"assistant","content":"The answer is 42."},{"role":"user","content":"next"}]}"""
        val replayed =
            bridge.openAiChatToGoogleBody(
                request(followUp),
                parse(followUp),
                JsonObject(emptyMap()),
            )
        val textPart =
            replayed["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray[0].jsonObject
        assertEquals("text-sig", textPart["thoughtSignature"]!!.jsonPrimitive.content)
    }

    @Test
    fun normalizesGeminiToolSchemas() {
        val body =
            """
            {"messages":[{"role":"user","content":"hi"}],"tools":[{"type":"function","function":{
              "name":"search",
              "parameters":{
                "type":"object",
                "properties":{
                  "query":{"type":"string","exclusiveMinimum":true,"minimum":1},
                  "tags":{"type":"array","items":[{"type":"string"}]}
                },
                "required":["query","missing"]
              }
            }}]}
            """
        val mapped =
            bridge.openAiChatToGoogleBody(request(body), parse(body), JsonObject(emptyMap()))
        val schema =
            mapped["tools"]!!
                .jsonArray[0]
                .jsonObject["functionDeclarations"]!!
                .jsonArray[0]
                .jsonObject["parametersJsonSchema"]!!
                .jsonObject
        val properties = schema["properties"]!!.jsonObject
        assertEquals(
            "1",
            properties["query"]!!.jsonObject["exclusiveMinimum"]!!.jsonPrimitive.content,
        )
        assertTrue("minimum" !in properties["query"]!!.jsonObject)
        assertTrue("prefixItems" in properties["tags"]!!.jsonObject)
        assertEquals(
            listOf("query"),
            schema["required"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun buildsModelScopedGenerateAndStreamUrls() {
        val bridge = GoogleGeminiChatBridge()
        val base = URI.create("https://opencode.ai/inference/google/v1beta")
        assertEquals(
            "https://opencode.ai/inference/google/v1beta/models/gemini-3.8-flash:generateContent",
            bridge.generateContentUri(base, "gemini-3.8-flash", stream = false).toString(),
        )
        assertEquals(
            "https://opencode.ai/inference/google/v1beta/models/gemini-3.8-flash:streamGenerateContent?alt=sse",
            bridge.generateContentUri(base, "gemini-3.8-flash", stream = true).toString(),
        )
    }

    private fun request(body: String): SubscriptionProxyRequest {
        val model =
            SubscriptionProxyModel(
                localId = "oc-zen-gemini-3.8-flash",
                upstreamId = "gemini-3.8-flash",
                providerId = "opencode",
                providerName = "OpenCode Zen",
                litellmProvider = "opencode",
                supportedRoutes = setOf(SubscriptionProxyRoute.CHAT_COMPLETIONS),
                maxOutputTokens = 65536,
            )
        return SubscriptionProxyRequest(
            SubscriptionProxyRoute.CHAT_COMPLETIONS,
            "req-gemini",
            model,
            parse(body),
        )
    }

    private fun parse(body: String): JsonObject {
        return JsonHelper.parseToJsonElementOrNull(body) as JsonObject
    }
}
