package de.moritzf.proxy.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class UpstreamErrorMapperTest {
    private val mapper = UpstreamErrorMapper()

    @Test
    fun quotaStreamFailureMapsToInsufficientQuota() {
        val mapped =
            mapper.mapStreamFailure(
                """{"type":"insufficient_quota","code":"insufficient_quota","message":"You exceeded your current quota."}"""
            )

        assertEquals(429, mapped.statusCode)
        val error = JsonHelper.parseToJsonElement(mapped.body).jsonObject["error"]!!.jsonObject
        assertEquals("insufficient_quota", error["type"]!!.jsonPrimitive.content)
        assertEquals("insufficient_quota", error["code"]!!.jsonPrimitive.content)
        assertEquals(
            "You exceeded your current quota.",
            error["message"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun rateLimitStreamFailureMapsTo429() {
        val mapped =
            mapper.mapStreamFailure(
                """{"type":"error","code":"rate_limit_exceeded","message":"Too many requests"}"""
            )

        assertEquals(429, mapped.statusCode)
        assertEquals(
            "Too many requests",
            JsonHelper.parseToJsonElement(mapped.body)
                .jsonObject["error"]!!
                .jsonObject["message"]!!
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun genericStreamFailureStays502() {
        val mapped = mapper.mapStreamFailure("""{"message":"Upstream broke"}""")

        assertEquals(502, mapped.statusCode)
        assertEquals(
            "upstream_error",
            JsonHelper.parseToJsonElement(mapped.body)
                .jsonObject["error"]!!
                .jsonObject["type"]!!
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun plainTextStreamFailureKeepsItsMessage() {
        val mapped = mapper.mapStreamFailure("No completed response found in SSE stream.")

        assertEquals(502, mapped.statusCode)
        assertEquals(
            "No completed response found in SSE stream.",
            JsonHelper.parseToJsonElement(mapped.body)
                .jsonObject["error"]!!
                .jsonObject["message"]!!
                .jsonPrimitive
                .content,
        )
    }
}
