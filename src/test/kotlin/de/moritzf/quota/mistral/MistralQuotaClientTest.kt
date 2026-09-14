package de.moritzf.quota.mistral

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class MistralQuotaClientTest {
    @Test
    fun buildRawResponseWrapsSessionAndApiKeyPayloads() {
        val raw = MistralQuotaClient.buildRawResponse(
            billingBody = """{"vibe_usage":1.5}""",
            vibeBody = """[{"result":{"data":{"json":{"usage_percentage":1.5}}}}]""",
            identityBody = """{"email":"a@b.c"}""",
            rateLimits = mapOf(
                "x-ratelimit-limit-tokens-minute" to "500000",
                "x-ratelimit-remaining-tokens-minute" to "499000",
            ),
        )
        assertTrue(raw.contains("\"session\""))
        assertTrue(raw.contains("\"billing\""))
        assertTrue(raw.contains("\"vibe\""))
        assertTrue(raw.contains("\"api_key\""))
        assertTrue(raw.contains("\"identity\""))
        assertTrue(raw.contains("\"rate_limits\""))
        assertTrue(raw.contains("500000"))
    }

    @Test
    fun buildRawResponseOmitsMissingSides() {
        val sessionOnly = MistralQuotaClient.buildRawResponse("""{"vibe_usage":0}""", null, null, null)
        assertTrue(sessionOnly.contains("\"session\""))
        assertTrue(!sessionOnly.contains("\"api_key\""))
    }

    @Test
    fun parseIdentityReadsOrgWorkspaceAndKeyName() {
        val body = """
            {
              "id": "user-1",
              "email": "user@example.com",
              "workspace": {"id": "ws-1", "name": "Default Workspace"},
              "organization": {"id": "org-1", "name": "Example Org"},
              "api_key": {"id": "key-1", "name": "Test-usage-plugin"}
            }
        """.trimIndent()

        val identity = MistralQuotaClient.parseIdentity(body)

        assertEquals("user@example.com", identity.email)
        assertEquals("Example Org", identity.organization?.name)
        assertEquals("Default Workspace", identity.workspace?.name)
        assertEquals("Test-usage-plugin", identity.apiKey?.name)
    }

    @Test
    fun parseIdentityIgnoresUnknownFields() {
        val identity = MistralQuotaClient.parseIdentity("""{"email":"a@b.c","extra":true}""")
        assertEquals("a@b.c", identity.email)
        assertNull(identity.organization)
    }

    @Test
    fun parseIdentityRejectsUnreadablePayload() {
        assertFailsWith<MistralQuotaException> {
            MistralQuotaClient.parseIdentity("not-json")
        }
    }

    @Test
    fun windowFromValuesComputesUsedPercentAndMinuteReset() {
        val now = Instant.fromEpochMilliseconds(1_780_000_030_000L)
        val window = assertNotNull(MistralQuotaClient.windowFromValues(limit = 500_000, remaining = 499_000, now = now))

        assertEquals(1_000, window.used)
        assertEquals(500_000, window.limit)
        assertEquals(499_000, window.remaining)
        assertEquals(0.2, window.usagePercent, 0.0001)
        assertEquals(60_000, window.periodDurationMs)
        assertEquals(Instant.fromEpochMilliseconds(1_780_000_080_000L), window.resetsAt)
    }

    @Test
    fun windowFromValuesReturnsNullWhenLimitMissing() {
        val now = Instant.fromEpochMilliseconds(1_780_000_000_000L)
        assertNull(MistralQuotaClient.windowFromValues(limit = 0, remaining = 0, now = now))
    }

    @Test
    fun parseSessionCookiesRequiresOrySession() {
        val session = MistralQuotaClient.parseSessionCookies(
            "Cookie: ory_session_abc=token; csrftoken=csrf-1; other=x",
        )
        assertEquals("csrf-1", session.csrfToken)
        assertEquals("csrftoken=csrf-1; ory_session_abc=token", session.consoleCookieHeader())
        assertFailsWith<MistralQuotaException> {
            MistralQuotaClient.parseSessionCookies("sessionid=nope")
        }
    }

    @Test
    fun encodeStoredSessionStripsQuotesAndRoundTripsFields() {
        val stored = MistralQuotaClient.encodeStoredSession(
            sessionName = "ory_session_coolcurranf83m3srkfl",
            sessionValue = "\"abc\"",
            csrfToken = "csrf-1",
        )
        val session = MistralQuotaClient.parseSessionCookies(stored)
        assertEquals("csrf-1", session.csrfToken)
        assertEquals("ory_session_coolcurranf83m3srkfl", session.sessionPairs.single().first)
        assertEquals("abc", session.sessionPairs.single().second)
        val fields = assertNotNull(MistralQuotaClient.storedSessionFields(stored))
        assertEquals("ory_session_coolcurranf83m3srkfl", fields.sessionName)
        assertEquals("abc", fields.sessionValue)
    }

    @Test
    fun parseVibeUsageReadsPercentAndReset() {
        val body = """
            [{"result":{"data":{"json":{"usage_percentage":42.5,"reset_at":"2026-09-01T00:00:00Z"}}}}]
        """.trimIndent()
        val vibe = assertNotNull(MistralQuotaClient.parseVibeUsage(body))
        assertEquals(42.5, vibe.usagePercent)
        assertEquals(Instant.parse("2026-09-01T00:00:00Z"), vibe.resetsAt)
    }

    @Test
    fun monthlyWindowPrefersVibePercentOverBilling() {
        val billing = MistralQuotaClient.parseBilling(
            """{"vibe_usage":10.0,"start_date":"2026-08-01","end_date":"2026-08-31"}""",
        )
        val vibe = MistralVibeUsage(42.5, Instant.parse("2026-09-01T00:00:00Z"))
        val window = assertNotNull(MistralQuotaClient.monthlyWindow(vibe, billing))
        assertEquals(42.5, window.usagePercent)
        assertEquals(Instant.parse("2026-09-01T00:00:00Z"), window.resetsAt)
        assertEquals(true, (window.periodDurationMs ?: 0L) > 0L)
    }
}
