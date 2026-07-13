package de.moritzf.proxy.logging

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class RequestLoggerRedactionTest {
    @Test
    fun redactsSensitiveFieldsInJsonBodies() {
        val body = """
            {
              "model": "gpt-5",
              "api_key": "sk-secret",
              "apiKey": "sk-secret-camel",
              "access_token": "at-123",
              "nested": {"refresh_token": "rt-456", "password": "hunter2"},
              "items": [{"session_cookie": "c=1", "text": "keep me"}]
            }
        """.trimIndent()

        val redacted = Json.parseToJsonElement(RequestLogger.redactBodyForLog(body)!!).jsonObject

        assertEquals("gpt-5", redacted["model"]?.jsonPrimitive?.content)
        assertEquals("[REDACTED]", redacted["api_key"]?.jsonPrimitive?.content)
        assertEquals("[REDACTED]", redacted["apiKey"]?.jsonPrimitive?.content)
        assertEquals("[REDACTED]", redacted["access_token"]?.jsonPrimitive?.content)
        val nested = redacted["nested"]?.jsonObject
        assertEquals("[REDACTED]", nested?.get("refresh_token")?.jsonPrimitive?.content)
        assertEquals("[REDACTED]", nested?.get("password")?.jsonPrimitive?.content)
        val item = redacted["items"]?.jsonArray?.first()?.jsonObject
        assertEquals("[REDACTED]", item?.get("session_cookie")?.jsonPrimitive?.content)
        assertEquals("keep me", item?.get("text")?.jsonPrimitive?.content)
    }

    @Test
    fun keepsTokenCountAndLimitFields() {
        val body = """
            {
              "max_tokens": 4096,
              "usage": {"total_tokens": 10, "input_tokens": 4, "output_tokens": 6}
            }
        """.trimIndent()

        val redacted = Json.parseToJsonElement(RequestLogger.redactBodyForLog(body)!!).jsonObject

        assertEquals("4096", redacted["max_tokens"]?.jsonPrimitive?.content)
        val usage = redacted["usage"]?.jsonObject
        assertEquals("10", usage?.get("total_tokens")?.jsonPrimitive?.content)
        assertEquals("4", usage?.get("input_tokens")?.jsonPrimitive?.content)
        assertEquals("6", usage?.get("output_tokens")?.jsonPrimitive?.content)
    }

    @Test
    fun passesNonJsonBodiesThroughUnchanged() {
        val sse = "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\ndata: [DONE]\n\n"
        assertSame(sse, RequestLogger.redactBodyForLog(sse))
    }

    @Test
    fun passesBlankAndNullBodiesThrough() {
        assertNull(RequestLogger.redactBodyForLog(null))
        assertEquals("", RequestLogger.redactBodyForLog(""))
    }
}