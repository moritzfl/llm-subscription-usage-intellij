package de.moritzf.quota.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class McpJsonTest {
    @Test
    fun returnsProviderJsonWhenBodyIsJson() {
        val body = """{"provider":"ok","items":[1,2]}"""

        assertEquals(body, McpJson.providerJsonOrRaw(body))
    }

    @Test
    fun wrapsProviderTextWhenBodyIsNotJson() {
        val result = JsonSupport.json.parseToJsonElement(McpJson.providerJsonOrRaw("temporarily unavailable"))

        assertEquals("temporarily unavailable", result.jsonObject["raw_response"]!!.jsonPrimitive.content)
    }
}
