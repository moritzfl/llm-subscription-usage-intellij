package de.moritzf.quota.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class RawResponseRedactorTest {
    @Test
    fun redactsCredentialsInsideEmbeddedJsonWithoutChangingItsType() {
        val embedded =
            """{"accessToken":"access-value","nested":[{"token":"token-value","usage":42}]}"""
        val response = buildJsonObject {
            put("profile", embedded)
            put("total_tokens", 123)
        }

        val result = RawResponseRedactor.redact(response).jsonObject

        assertTrue(result.getValue("profile").jsonPrimitive.isString)
        assertFalse(result.toString().contains("access-value"))
        assertFalse(result.toString().contains("token-value"))
        assertTrue(result.toString().contains("42"))
        assertEquals(JsonPrimitive(123), result["total_tokens"])
    }

    @Test
    fun preservesUnchangedJsonAndOrdinaryDiagnosticText() {
        for (raw in
            listOf(
                "{ \"remaining\": 12, \"models\": [\"mistral\"] }",
                "Request failed (HTTP 429). Retry later.",
                "API key missing. Log in from settings.",
            )) {
            assertEquals(raw, RawResponseRedactor.redact(raw))
        }
    }

    @Test
    fun removesKnownSecretFromUnlabelledTextAndEscapedJsonStrings() {
        val secret = "synthetic-\"credential"
        val json = buildJsonObject { put("message", "Rejected $secret") }.toString()

        assertEquals(
            "Rejected [REDACTED]",
            RawResponseRedactor.redactText("Rejected $secret", secret),
        )
        val result =
            JsonSupport.json.parseToJsonElement(RawResponseRedactor.redactText(json, secret))
        assertEquals(
            "Rejected [REDACTED]",
            result.jsonObject.getValue("message").jsonPrimitive.content,
        )
    }

    @Test
    fun redactsPrefixedErrorBodyAndKeepsStatusAndQuotaFields() {
        val raw =
            """Usage request failed: 403 {"error":{"apiKey":"hidden-key","message":"denied"},"remaining":17}"""

        val result = RawResponseRedactor.redactText(raw)

        assertFalse(result.contains("hidden-key"))
        assertTrue(result.startsWith("Usage request failed: 403 "))
        assertTrue(result.contains("denied"))
        assertTrue(result.contains("17"))
    }

    @Test
    fun redactsPlaintextHeadersAndCredentialAssignments() {
        for (raw in
            listOf(
                "Request rejected: Bearer synthetic-credential",
                "Request rejected: Bearer \"synthetic-credential trailing-credential\"",
                "Authorization: Basic synthetic-credential",
                "Cookie: first=synthetic-credential; second=another-credential",
                "url?api_key=synthetic-credential&retry=1",
                "invalid header value: \"Bearer synthetic-credential\"trailing-credential\nlast-credential\"",
                "upstream: {\"refresh_token\":\"synthetic-credential\\\"trailing-credential\", broken",
                "upstream: {\"refresh_token\":\"synthetic-credential trailing-credential",
            )) {
            val result = RawResponseRedactor.redactText(raw)
            assertFalse(result.contains("-credential"), result)
            assertTrue(result.contains("[REDACTED]"), result)
            assertEquals(result, RawResponseRedactor.redactText(result))
        }
    }
}
