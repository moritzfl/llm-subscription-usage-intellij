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

        assertEquals("Claude auth expired. Log in to Claude again from settings.", exception.message)
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
    }
}
