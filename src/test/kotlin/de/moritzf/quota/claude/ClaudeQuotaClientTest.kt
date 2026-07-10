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
    }
}
