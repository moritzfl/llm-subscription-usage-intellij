package de.moritzf.proxy.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CodexReserveHopTest {
    private val hop = CodexReserveHop()

    @Test
    fun rewritesLunaResponsesPostToGptReserve() {
        assertTrue(hop.isEligibleRequest("/responses", "POST", """{"model":"gpt-6-luna"}"""))
        assertTrue(hop.isEligibleRequest("/responses", "POST", """{"model":"gpt-5.6-luna"}"""))
        val rewritten = hop.rewriteRequestToReserve(
            """{"model":"gpt-5.6-luna","reasoning":{"effort":"ultra"},"stream":true}""",
        )
        val root = de.moritzf.proxy.util.Json.INSTANCE.parseToJsonElement(rewritten!!).jsonObject
        assertEquals("gpt-reserve", root["model"]!!.jsonPrimitive.content)
        assertEquals("max", root["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun doesNotHopWhenAlreadyReserve() {
        assertFalse(hop.isEligibleRequest("/responses", "POST", """{"model":"gpt-reserve"}"""))
        assertNull(hop.rewriteRequestToReserve("""{"model":"gpt-reserve"}"""))
    }

    @Test
    fun doesNotHopHigherTierModels() {
        assertFalse(hop.isEligibleRequest("/responses", "POST", """{"model":"gpt-6-sol"}"""))
        assertFalse(hop.isEligibleRequest("/responses", "POST", """{"model":"gpt-5.6-sol"}"""))
        assertFalse(hop.isEligibleRequest("/responses", "POST", """{"model":"gpt-5.6-terra"}"""))
        assertFalse(hop.isEligibleRequest("/responses", "POST", """{"model":"gpt-6-astra"}"""))
        assertFalse(hop.isEligibleRequest("/responses", "POST", """{"model":"gpt-5.5"}"""))
        assertNull(hop.rewriteRequestToReserve("""{"model":"gpt-5.6-sol"}"""))
    }

    @Test
    fun detectsUsageLimitBodies() {
        assertTrue(hop.isUsageLimit("""{"detail":"You've hit your usage limit. usage_limit_reached"}"""))
        assertFalse(hop.isUsageLimit("""{"detail":"model not found"}"""))
    }
}
