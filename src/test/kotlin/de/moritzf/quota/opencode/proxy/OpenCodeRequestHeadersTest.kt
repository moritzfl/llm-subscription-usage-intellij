package de.moritzf.quota.opencode.proxy

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenCodeRequestHeadersTest {
    @Test
    fun keepsIncomingSessionAndStripsHeaderInjection() {
        val body = json()

        assertEquals("ses_keep", OpenCodeRequestHeaders.sessionId("ses_keep", body))
        assertEquals("ses_keep", OpenCodeRequestHeaders.sessionId("ses_keep\r\nAuthorization: bearer", body))
    }

    @Test
    fun usesPromptCacheKeyWhenTheClientSendsNoSession() {
        val body = json(promptCacheKey = "lsu-fim-chat-v2")

        assertEquals("lsu-fim-chat-v2", OpenCodeRequestHeaders.sessionId(null, body))
        assertEquals("lsu-fim-chat-v2", OpenCodeRequestHeaders.sessionId("   ", body))
    }

    @Test
    fun hashesTheFirstMessageWhenNothingElseIdentifiesTheConversation() {
        val first = OpenCodeRequestHeaders.sessionId(null, json(firstMessage = "hi"))
        val same = OpenCodeRequestHeaders.sessionId(null, json(firstMessage = "hi"))
        val other = OpenCodeRequestHeaders.sessionId(null, json(firstMessage = "bye"))

        assertTrue(first.startsWith("lsu-"))
        assertEquals(first, same)
        assertTrue(first != other)
    }

    private fun json(promptCacheKey: String? = null, firstMessage: String = "hi"): JsonObject {
        return buildJsonObject {
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "user"); put("content", firstMessage) })
            })
            if (promptCacheKey != null) put("prompt_cache_key", JsonPrimitive(promptCacheKey))
        }
    }
}
