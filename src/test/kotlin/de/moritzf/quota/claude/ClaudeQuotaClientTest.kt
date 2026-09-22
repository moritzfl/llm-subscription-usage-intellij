package de.moritzf.quota.claude

import kotlin.time.Instant
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Optional
import java.util.concurrent.CompletableFuture
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaudeQuotaClientTest {
    @Test
    fun parseQuotaExtractsSessionWeeklyAndExtraUsage() {
        val fetchedAt = Instant.parse("2026-06-16T12:00:00Z")
        val quota = ClaudeQuotaClient.parseQuota(USAGE_RESPONSE, fetchedAt)

        assertEquals(fetchedAt, quota.fetchedAt)
        assertEquals(12.5, quota.fiveHourUsage?.usagePercent)
        assertEquals(Instant.parse("2025-12-25T12:00:00Z"), quota.fiveHourUsage?.resetsAt)
        assertEquals(30.0, quota.sevenDayUsage?.usagePercent)
        assertEquals(5.0, quota.sevenDaySonnetUsage?.usagePercent)
        assertEquals(8.0, quota.sevenDayOpusUsage?.usagePercent)
        assertEquals(2.0, quota.sevenDayOauthAppsUsage?.usagePercent)
        assertEquals(18.0, quota.routinesUsage?.usagePercent)
        assertEquals(true, quota.extraUsage?.isEnabled)
        assertEquals(2050L, quota.extraUsage?.monthlyLimitCredits)
        assertEquals(325L, quota.extraUsage?.usedCredits)
        assertEquals(15.85, quota.extraUsage?.usagePercent)
        assertEquals("USD", quota.extraUsage?.currency)
    }

    @Test
    fun parseQuotaExtractsModelScopedLimits() {
        val quota = ClaudeQuotaClient.parseQuota(SCOPED_LIMITS_RESPONSE)

        assertEquals(0.0, quota.fiveHourUsage?.usagePercent)
        assertEquals(17.0, quota.sevenDayUsage?.usagePercent)

        // Unscoped session/weekly_all entries duplicate top-level windows and are skipped.
        assertEquals(1, quota.scopedLimits.size)
        val fable = quota.scopedLimits.single()
        assertEquals("Weekly (Fable)", fable.label)
        assertEquals(34.0, fable.usagePercent)
        assertEquals(Instant.parse("2026-07-26T05:00:00.030850Z"), fable.resetsAt)

        // Scoped limits participate in usage/activity fractions.
        assertEquals(0.34, quota.usageFraction())
        assertEquals(0.51, quota.activityFraction()!!, 0.0001)
    }

    @Test
    fun parseQuotaIgnoresMalformedLimitEntries() {
        val json = """
            {
              "five_hour": {"utilization": 5.0, "resets_at": null},
              "limits": [
                {"kind": "weekly_scoped", "group": "weekly", "percent": 12, "scope": {"model": {"id": null, "display_name": ""}, "surface": null}},
                {"kind": "weekly_scoped", "group": "weekly", "scope": {"model": {"display_name": "Fable"}}},
                {"kind": "unknown_kind", "percent": 7, "scope": {"model": {"id": "model-x"}}},
                "not-an-object-is-tolerated-by-kind-nulls"
              ]
            }
        """.trimIndent().replace("\"not-an-object-is-tolerated-by-kind-nulls\"", "{}")

        val quota = ClaudeQuotaClient.parseQuota(json)

        // Blank display name, missing percent, and empty entries are dropped; id fallback is kept.
        assertEquals(1, quota.scopedLimits.size)
        assertEquals("Unknown_kind (model-x)", quota.scopedLimits.single().label)
        assertEquals(7.0, quota.scopedLimits.single().usagePercent)
    }

    @Test
    fun parseQuotaAcceptsDecimalCreditsAndSpendBlock() {
        val quota = ClaudeQuotaClient.parseQuota(DECIMAL_CREDITS_RESPONSE)

        assertEquals(0.0, quota.fiveHourUsage?.usagePercent)
        assertEquals(0.0, quota.sevenDayUsage?.usagePercent)
        assertEquals(Instant.parse("2026-07-31T06:59:59.574963Z"), quota.sevenDayUsage?.resetsAt)

        assertEquals(true, quota.extraUsage?.isEnabled)
        assertEquals(10000L, quota.extraUsage?.monthlyLimitCredits)
        assertEquals(4403L, quota.extraUsage?.usedCredits)
        assertEquals(44.03, quota.extraUsage?.usagePercent)
        assertEquals("EUR", quota.extraUsage?.currency)

        // Scoped weekly limit for one model still parses; unscoped entries are skipped.
        assertEquals(1, quota.scopedLimits.size)
        assertEquals("Weekly (Fable)", quota.scopedLimits.single().label)
    }

    @Test
    fun parseQuotaKeepsParsableSectionsWhenOtherBlocksAreMalformed() {
        val json = """
            {
              "five_hour": {"utilization": 12.0, "resets_at": null},
              "seven_day": {"utilization": "broken"},
              "extra_usage": ["totally", "reshaped"],
              "limits": [
                {"kind": "weekly_scoped", "group": "weekly", "percent": 34, "scope": {"model": {"display_name": "Fable"}}},
                "garbage-entry",
                {"kind": "weekly_scoped", "group": "weekly", "percent": {"nested": true}, "scope": {"model": {"display_name": "Broken"}}}
              ]
            }
        """.trimIndent()

        val quota = ClaudeQuotaClient.parseQuota(json)

        // The parsable window and limit entry survive; malformed blocks are dropped.
        assertEquals(12.0, quota.fiveHourUsage?.usagePercent)
        assertNull(quota.sevenDayUsage)
        assertNull(quota.extraUsage)
        assertEquals(1, quota.scopedLimits.size)
        assertEquals("Weekly (Fable)", quota.scopedLimits.single().label)
        assertEquals(34.0, quota.scopedLimits.single().usagePercent)
    }

    @Test
    fun parseQuotaReportsChangedPayload() {
        val exception = assertFailsWith<ClaudeQuotaException> {
            ClaudeQuotaClient.parseQuota("""{"ok":true}""")
        }
        assertEquals("Claude usage response changed.", exception.message)
    }

    @Test
    fun fetchQuotaUsesOAuthUsageEndpointAndHeaders() {
        val httpClient = FakeHttpClient(FakeResponseSpec(USAGE_RESPONSE))
        val client = ClaudeQuotaClient(httpClient, URI.create("https://api.anthropic.test/api/oauth/usage"))

        val quota = client.fetchQuota("token-123")

        assertEquals(12.5, quota.fiveHourUsage?.usagePercent)
        assertEquals(listOf("https://api.anthropic.test/api/oauth/usage"), httpClient.requests.map { it.uri().toString() })
        val request = httpClient.requests.single()
        assertEquals("Bearer token-123", request.headers().firstValue("Authorization").orElse(null))
        assertEquals("oauth-2025-04-20", request.headers().firstValue("anthropic-beta").orElse(null))
        assertEquals("claude-cli/2.1.87 (external, cli)", request.headers().firstValue("User-Agent").orElse(null))
        assertTrue(quota.rawJson?.contains("five_hour") == true)
    }

    @Test
    fun fetchQuotaRejectsMissingTokenBeforeNetworkCall() {
        val httpClient = FakeHttpClient(FakeResponseSpec(USAGE_RESPONSE))
        val client = ClaudeQuotaClient(httpClient, URI.create("https://api.anthropic.test/api/oauth/usage"))

        val exception = assertFailsWith<ClaudeQuotaException> {
            client.fetchQuota(" ")
        }

        assertEquals("Claude login required. Log in from Claude settings.", exception.message)
        assertEquals(emptyList(), httpClient.requests)
    }

    @Test
    fun fetchQuotaTreatsAuthRejectionAsExpiredLogin() {
        val client = ClaudeQuotaClient(
            FakeHttpClient(FakeResponseSpec("""{"error":"unauthorized"}""", status = 401)),
            URI.create("https://api.anthropic.test/api/oauth/usage"),
        )

        val exception = assertFailsWith<ClaudeQuotaException> {
            client.fetchQuota("token-123")
        }

        assertEquals("Claude usage API rejected the access token (HTTP 401).", exception.message)
        assertEquals(401, exception.statusCode)
    }

    private data class FakeResponseSpec(val body: String, val status: Int = 200)

    private class FakeHttpClient(private vararg val responses: FakeResponseSpec) : HttpClient() {
        val requests = mutableListOf<HttpRequest>()
        private var index = 0

        override fun <T : Any?> send(request: HttpRequest, responseBodyHandler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
            requests.add(request)
            val response = responses[index++]
            @Suppress("UNCHECKED_CAST")
            return FakeResponse(response.body, response.status, request) as HttpResponse<T>
        }

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): CompletableFuture<HttpResponse<T>> = throw UnsupportedOperationException()

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
            pushPromiseHandler: HttpResponse.PushPromiseHandler<T>,
        ): CompletableFuture<HttpResponse<T>> = throw UnsupportedOperationException()

        override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()
        override fun connectTimeout(): Optional<java.time.Duration> = Optional.empty()
        override fun followRedirects(): Redirect = Redirect.NEVER
        override fun proxy(): Optional<ProxySelector> = Optional.empty()
        override fun sslContext(): SSLContext = SSLContext.getDefault()
        override fun sslParameters(): SSLParameters = SSLParameters()
        override fun authenticator(): Optional<Authenticator> = Optional.empty()
        override fun version(): Version = Version.HTTP_1_1
        override fun executor(): Optional<java.util.concurrent.Executor> = Optional.empty()
    }

    private class FakeResponse(
        private val body: String,
        private val status: Int,
        private val request: HttpRequest,
    ) : HttpResponse<String> {
        override fun statusCode(): Int = status
        override fun request(): HttpRequest = request
        override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()
        override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }
        override fun body(): String = body
        override fun sslSession(): Optional<SSLSession> = Optional.empty()
        override fun uri(): URI = request.uri()
        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
    }

    companion object {
        private val USAGE_RESPONSE = """
            {
              "five_hour": { "utilization": 12.5, "resets_at": "2025-12-25T12:00:00.000Z" },
              "seven_day": { "utilization": 30, "resets_at": "2025-12-31T00:00:00.000Z" },
              "seven_day_sonnet": { "utilization": 5, "resets_at": "2025-12-31T00:00:00.000Z" },
              "seven_day_opus": { "utilization": 8, "resets_at": "2025-12-31T00:00:00.000Z" },
              "seven_day_oauth_apps": { "utilization": 2, "resets_at": "2025-12-31T00:00:00.000Z" },
              "seven_day_routines": { "utilization": 18, "resets_at": "2025-12-31T00:00:00.000Z" },
              "extra_usage": {
                "is_enabled": true,
                "monthly_limit": 2050,
                "used_credits": 325,
                "utilization": 15.85,
                "currency": "USD"
              }
            }
        """.trimIndent()

        /** Real-world payload shape with a model-scoped weekly limit in the `limits` array. */
        private val SCOPED_LIMITS_RESPONSE = """
            {
              "five_hour": {"utilization": 0.0, "resets_at": null, "limit_dollars": null, "used_dollars": null, "remaining_dollars": null},
              "seven_day": {"utilization": 17.0, "resets_at": "2026-07-26T05:00:00.030579+00:00", "limit_dollars": null, "used_dollars": null, "remaining_dollars": null},
              "seven_day_oauth_apps": null,
              "seven_day_opus": null,
              "seven_day_sonnet": null,
              "extra_usage": {"is_enabled": false, "monthly_limit": null, "used_credits": null, "utilization": null, "currency": null},
              "limits": [
                {"kind": "session", "group": "session", "percent": 0, "severity": "normal", "resets_at": null, "scope": null, "is_active": false},
                {"kind": "weekly_all", "group": "weekly", "percent": 17, "severity": "normal", "resets_at": "2026-07-26T05:00:00.030579+00:00", "scope": null, "is_active": false},
                {"kind": "weekly_scoped", "group": "weekly", "percent": 34, "severity": "normal", "resets_at": "2026-07-26T05:00:00.030850+00:00", "scope": {"model": {"id": null, "display_name": "Fable"}, "surface": null}, "is_active": true}
              ],
              "member_dashboard_available": false
            }
        """.trimIndent()

        /** Real-world payload with decimal `used_credits`, a `spend` block, and null feature windows. */
        private val DECIMAL_CREDITS_RESPONSE = """
            {
              "five_hour": {
                "utilization": 0.0,
                "resets_at": null,
                "limit_dollars": null,
                "used_dollars": null,
                "remaining_dollars": null
              },
              "seven_day": {
                "utilization": 0.0,
                "resets_at": "2026-07-31T06:59:59.574963+00:00",
                "limit_dollars": null,
                "used_dollars": null,
                "remaining_dollars": null
              },
              "seven_day_oauth_apps": null,
              "seven_day_opus": null,
              "seven_day_sonnet": null,
              "seven_day_cowork": null,
              "seven_day_omelette": null,
              "tangelo": null,
              "iguana_necktie": null,
              "omelette_promotional": null,
              "nimbus_quill": null,
              "cinder_cove": null,
              "amber_ladder": null,
              "extra_usage": {
                "is_enabled": true,
                "monthly_limit": 10000,
                "used_credits": 4403.0,
                "utilization": 44.03,
                "currency": "EUR",
                "decimal_places": 2,
                "disabled_reason": null,
                "user_disabled": false,
                "spend_limit_reached": false,
                "credits_ever_enabled": true,
                "daily": null,
                "weekly": null
              },
              "limits": [
                {
                  "kind": "session",
                  "group": "session",
                  "percent": 0,
                  "severity": "normal",
                  "resets_at": null,
                  "scope": null,
                  "is_active": true
                },
                {
                  "kind": "weekly_all",
                  "group": "weekly",
                  "percent": 0,
                  "severity": "normal",
                  "resets_at": "2026-07-31T06:59:59.574963+00:00",
                  "scope": null,
                  "is_active": false
                },
                {
                  "kind": "weekly_scoped",
                  "group": "weekly",
                  "percent": 0,
                  "severity": "normal",
                  "resets_at": null,
                  "scope": {
                    "model": {
                      "id": null,
                      "display_name": "Fable"
                    },
                    "surface": null
                  },
                  "is_active": false
                }
              ],
              "spend": {
                "used": {"amount_minor": 4403, "currency": "EUR", "exponent": 2},
                "limit": {"amount_minor": 10000, "currency": "EUR", "exponent": 2},
                "percent": 44,
                "severity": "normal",
                "enabled": true,
                "disabled_reason": null,
                "cap": {"money": {"amount_minor": 10000, "currency": "EUR", "exponent": 2}, "credits": null},
                "balance": null,
                "auto_reload": null,
                "disclaimer": "Usage credits cover you when you hit your plan limits. [Learn more](https://support.claude.com/articles/12429409)",
                "can_purchase_credits": false,
                "can_toggle": false
              },
              "member_dashboard_available": false
            }
        """.trimIndent()
    }
}
