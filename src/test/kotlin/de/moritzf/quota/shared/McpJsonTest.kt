package de.moritzf.quota.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class McpJsonTest {
    @Test
    fun sanitizesErrorMessagesBeforeJsonEncoding() {
        val result = McpJson.error("Request failed: Bearer synthetic-credential")
        val message =
            JsonSupport.json
                .parseToJsonElement(result)
                .jsonObject
                .getValue("error")
                .jsonPrimitive
                .content

        assertEquals("Request failed: Bearer [REDACTED]", message)
        assertFalse(result.contains("synthetic-credential"))
        assertTrue(result.contains("Request failed"))
    }

    @Test
    fun returnsProviderJsonWhenBodyIsJson() {
        val body = """{"provider":"ok","items":[1,2]}"""

        assertEquals(body, McpJson.providerJsonOrRaw(body))
    }

    @Test
    fun wrapsProviderTextWhenBodyIsNotJson() {
        val result =
            JsonSupport.json.parseToJsonElement(
                McpJson.providerJsonOrRaw("temporarily unavailable")
            )

        assertEquals(
            "temporarily unavailable",
            result.jsonObject["raw_response"]!!.jsonPrimitive.content,
        )
    }
}
