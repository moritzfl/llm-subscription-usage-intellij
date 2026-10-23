package de.moritzf.proxy.sse

import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class SseCollectorTest {
    @Test
    fun incompleteEventIsATerminalResponse() {
        val response =
            collect(
                sse(
                    "response.created",
                    """{"type":"response.created","response":{"id":"resp_1"}}""",
                ) +
                    sse(
                        "response.output_text.delta",
                        """{"type":"response.output_text.delta","delta":"partial"}""",
                    ) +
                    sse(
                        "response.incomplete",
                        """{"type":"response.incomplete","response":{"id":"resp_1","status":"incomplete","output":[],"usage":{"input_tokens":4,"output_tokens":2}}}""",
                    )
            )

        assertEquals("incomplete", response["status"]?.jsonPrimitive?.content)
        assertEquals("resp_1", response["id"]?.jsonPrimitive?.content)
        assertEquals("partial", outputText(response))
    }

    @Test
    fun incompleteEventWithoutStatusReportsIncomplete() {
        val response =
            collect(
                sse(
                    "response.incomplete",
                    """{"type":"response.incomplete","response":{"id":"resp_1","output":[{"type":"message","content":[{"type":"output_text","text":"cut off"}]}]}}""",
                )
            )

        assertEquals("incomplete", response["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun completedEventWithoutStatusReportsCompleted() {
        val response =
            collect(
                sse(
                    "response.completed",
                    """{"type":"response.completed","response":{"id":"resp_1","output":[{"type":"message","content":[{"type":"output_text","text":"done"}]}]}}""",
                )
            )

        assertEquals("completed", response["status"]?.jsonPrimitive?.content)
        assertEquals("done", outputText(response))
    }

    @Test
    fun doneSentinelWithoutTerminalEventSynthesizesCompletedResponse() {
        val response =
            collect(
                sse(
                    "response.output_text.delta",
                    """{"type":"response.output_text.delta","delta":"hello"}""",
                ) + "data: [DONE]\n\n"
            )

        assertEquals("completed", response["status"]?.jsonPrimitive?.content)
        assertEquals("hello", outputText(response))
    }

    @Test
    fun truncatedStreamKeepsPartialOutputAsIncomplete() {
        val response =
            collect(
                sse(
                    "response.created",
                    """{"type":"response.created","response":{"id":"resp_1","model":"gpt-6.1-sol"}}""",
                ) +
                    sse(
                        "response.output_text.delta",
                        """{"type":"response.output_text.delta","delta":"hel"}""",
                    ) +
                    sse(
                        "response.output_text.delta",
                        """{"type":"response.output_text.delta","delta":"lo"}""",
                    )
            )

        assertEquals("incomplete", response["status"]?.jsonPrimitive?.content)
        assertEquals("resp_1", response["id"]?.jsonPrimitive?.content)
        assertEquals("gpt-6.1-sol", response["model"]?.jsonPrimitive?.content)
        assertEquals("hello", outputText(response))
    }

    @Test
    fun loneErrorEventBecomesFailedResponse() {
        val response =
            collect(
                sse(
                    "error",
                    """{"type":"error","code":"rate_limit_exceeded","message":"Too many requests"}""",
                )
            )

        assertEquals("failed", response["status"]?.jsonPrimitive?.content)
        val error = response["error"] as JsonObject
        assertEquals("rate_limit_exceeded", error["code"]?.jsonPrimitive?.content)
        assertEquals("Too many requests", error["message"]?.jsonPrimitive?.content)
    }

    @Test
    fun zenQuotaErrorEventWithoutSseEventNameBecomesFailedResponse() {
        // Mirrors the OpenAI-compatible error event OpenCode Zen sends on an HTTP 200 stream when
        // the plan quota is exhausted: no `event:` line and a nested error object.
        val response =
            collect(
                "data: {\"type\":\"error\",\"sequence_number\":2,\"error\":{" +
                    "\"type\":\"insufficient_quota\",\"code\":\"insufficient_quota\"," +
                    "\"message\":\"You exceeded your current quota, please check your plan and " +
                    "billing details.\",\"param\":null}}\n\n"
            )

        assertEquals("failed", response["status"]?.jsonPrimitive?.content)
        val error = response["error"] as JsonObject
        assertEquals("insufficient_quota", error["code"]?.jsonPrimitive?.content)
        assertEquals(
            "You exceeded your current quota, please check your plan and billing details.",
            error["message"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun emptyStreamStillFails() {
        assertFailsWith<IOException> { collect("") }
    }

    private fun collect(body: String): JsonObject =
        SseCollector.collectCompletedResponse(
            ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
        )

    private fun sse(event: String, data: String): String = "event: $event\ndata: $data\n\n"

    private fun outputText(response: JsonObject): String {
        val output = response["output"] as JsonArray
        return output
            .flatMap { (it as JsonObject)["content"] as? JsonArray ?: emptyList() }
            .mapNotNull { (it as JsonObject)["text"]?.jsonPrimitive?.content }
            .joinToString("")
    }
}
