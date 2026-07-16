package de.moritzf.quota.supergrok

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

class SuperGrokQuotaClientTest {
    @Test
    fun parseQuotaExtractsWeeklyCreditsAndPlan() {
        val fetchedAt = Instant.parse("2026-07-10T12:00:00Z")
        val quota = SuperGrokQuotaClient.parseQuota(
            weeklyBillingJson = BILLING_WEEKLY_RESPONSE,
            settingsJson = SETTINGS_RESPONSE,
            fetchedAt = fetchedAt,
        )

        assertEquals("SuperGrok Heavy", quota.plan)
        assertEquals("xai-oauth-cli-proxy", quota.authSource)
        assertEquals(fetchedAt, quota.fetchedAt)
        assertEquals(0L, quota.onDemandCap)
        assertTrue(quota.isUnifiedBilling)
        assertEquals("USAGE_PERIOD_TYPE_WEEKLY", quota.periodType)
        val usage = quota.creditUsage ?: error("credit usage missing")
        assertEquals("Weekly credits", usage.label)
        assertEquals(100.0, usage.usagePercent)
        assertEquals(Instant.parse("2026-07-14T16:34:03.633192+00:00"), usage.resetsAt)
    }

    @Test
    fun parseQuotaReportsChangedBillingPayload() {
        val exception = assertFailsWith<SuperGrokQuotaException> {
            SuperGrokQuotaClient.parseQuota(weeklyBillingJson = """{"ok":true}""")
        }

        assertEquals("Grok billing response changed.", exception.message)
    }

    @Test
    fun parseQuotaKeepsBillingDetailsWhenUsageIsUnavailable() {
        val quota = SuperGrokQuotaClient.parseQuota(weeklyBillingJson = BILLING_CONFIG_ONLY_RESPONSE)

        assertNull(quota.creditUsage)
        assertTrue(quota.isUnifiedBilling)
        assertEquals("USAGE_PERIOD_TYPE_WEEKLY", quota.periodType)
    }

    @Test
    fun parseQuotaIgnoresMalformedOptionalFieldsInWrappedBillingPayload() {
        val quota = SuperGrokQuotaClient.parseQuota(weeklyBillingJson = WRAPPED_BILLING_RESPONSE)

        assertNull(quota.creditUsage)
        assertTrue(quota.isUnifiedBilling)
        assertEquals("USAGE_PERIOD_TYPE_WEEKLY", quota.periodType)
    }

    @Test
    fun parseQuotaFallsBackToProductUsagePercent() {
        val quota = SuperGrokQuotaClient.parseQuota(weeklyBillingJson = BILLING_PRODUCT_USAGE_ONLY_RESPONSE)
        assertEquals(16.0, quota.creditUsage?.usagePercent)
        assertEquals("Weekly credits", quota.creditUsage?.label)
    }

    @Test
    fun unifiedPercentOnlyWindowIsNotExhaustedAtLowUsage() {
        val window = SuperGrokUsageWindow(
            label = "Weekly credits",
            used = 0,
            limit = 0,
            usagePercent = 7.0,
        )
        assertEquals(false, window.isExhausted())
        assertEquals(true, window.copy(usagePercent = 100.0).isExhausted())
        assertEquals(true, SuperGrokUsageWindow(used = 10, limit = 10, usagePercent = 50.0).isExhausted())
    }

    @Test
    fun fetchQuotaUsesWeeklyBillingEndpointAndSettings() {
        val httpClient = FakeHttpClient(
            FakeResponseSpec(BILLING_WEEKLY_RESPONSE),
            FakeResponseSpec(SETTINGS_RESPONSE),
        )
        val client = SuperGrokQuotaClient(httpClient, URI.create("https://grok.test/v1/"))

        val quota = client.fetchQuota("token-123")

        assertEquals("SuperGrok Heavy", quota.plan)
        assertEquals(
            listOf("https://grok.test/v1/billing?format=credits", "https://grok.test/v1/settings"),
            httpClient.requests.map { it.uri().toString() },
        )
        httpClient.requests.forEach { request ->
            assertEquals("Bearer token-123", request.headers().firstValue("Authorization").orElse(null))
            assertEquals("xai-grok-cli", request.headers().firstValue("X-XAI-Token-Auth").orElse(null))
            assertEquals("application/json", request.headers().firstValue("Accept").orElse(null))
        }
        assertTrue(quota.rawJson?.contains("\"billing\"") == true)
        assertTrue(quota.rawJson?.contains("\"settings\"") == true)
    }

    @Test
    fun fetchQuotaRejectsMissingTokenBeforeNetworkCall() {
        val httpClient = FakeHttpClient(FakeResponseSpec(BILLING_WEEKLY_RESPONSE))
        val client = SuperGrokQuotaClient(httpClient, URI.create("https://grok.test/v1/"))

        val exception = assertFailsWith<SuperGrokQuotaException> {
            client.fetchQuota(" ")
        }

        assertEquals("Grok login required. Log in from SuperGrok settings.", exception.message)
        assertEquals(emptyList(), httpClient.requests)
    }

    @Test
    fun fetchQuotaTreatsAuthRejectionAsExpiredLogin() {
        val client = SuperGrokQuotaClient(
            FakeHttpClient(FakeResponseSpec("""{"error":"forbidden"}""", status = 403)),
            URI.create("https://grok.test/v1/"),
        )

        val exception = assertFailsWith<SuperGrokQuotaException> {
            client.fetchQuota("token-123")
        }

        assertEquals("Grok auth expired. Log in to SuperGrok again from settings.", exception.message)
        assertEquals(403, exception.statusCode)
    }

    private data class FakeResponseSpec(val body: String, val status: Int = 200)

    private class FakeHttpClient(private vararg val responses: FakeResponseSpec) : HttpClient() {
        val requests = mutableListOf<HttpRequest>()
        private var index = 0

        override fun <T : Any?> send(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>
        ): HttpResponse<T> {
            requests.add(request)
            val response = responses.getOrElse(index) { responses.last() }
            index++
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

    private companion object {
        private val BILLING_WEEKLY_RESPONSE = """
            {
              "config": {
                "currentPeriod": {
                  "type": "USAGE_PERIOD_TYPE_WEEKLY",
                  "start": "2026-07-07T16:34:03.633192+00:00",
                  "end": "2026-07-14T16:34:03.633192+00:00"
                },
                "creditUsagePercent": 100.0,
                "onDemandCap": {"val": 0},
                "onDemandUsed": {"val": 0},
                "isUnifiedBillingUser": true,
                "billingPeriodStart": "2026-07-07T16:34:03.633192+00:00",
                "billingPeriodEnd": "2026-07-14T16:34:03.633192+00:00"
              }
            }
        """.trimIndent()

        private val BILLING_CONFIG_ONLY_RESPONSE = """
            {
              "config": {
                "currentPeriod": {
                  "type": "USAGE_PERIOD_TYPE_WEEKLY",
                  "start": "2026-07-14T16:34:03.633192+00:00",
                  "end": "2026-07-21T16:34:03.633192+00:00"
                },
                "onDemandCap": {"val": 0},
                "onDemandUsed": {"val": 0},
                "isUnifiedBillingUser": true,
                "billingPeriodStart": "2026-07-14T16:34:03.633192+00:00",
                "billingPeriodEnd": "2026-07-21T16:34:03.633192+00:00"
              }
            }
        """.trimIndent()

        private val BILLING_PRODUCT_USAGE_ONLY_RESPONSE = """
            {
              "config": {
                "currentPeriod": {
                  "type": "USAGE_PERIOD_TYPE_WEEKLY",
                  "start": "2026-07-14T16:34:03.633192+00:00",
                  "end": "2026-07-21T16:34:03.633192+00:00"
                },
                "productUsage": [{"product": "Api", "usagePercent": 16.0}],
                "isUnifiedBillingUser": true,
                "billingPeriodStart": "2026-07-14T16:34:03.633192+00:00",
                "billingPeriodEnd": "2026-07-21T16:34:03.633192+00:00"
              }
            }
        """.trimIndent()

        private val WRAPPED_BILLING_RESPONSE = """
            {
              "authSource": "xai-oauth-cli-proxy",
              "billing": {
                "config": {
                  "currentPeriod": {
                    "type": "USAGE_PERIOD_TYPE_WEEKLY",
                    "start": "2026-07-14T16:34:03.633192+00:00",
                    "end": "2026-07-21T16:34:03.633192+00:00"
                  },
                  "onDemandCap": {"val": "not-a-number"},
                  "isUnifiedBillingUser": true,
                  "billingPeriodStart": "2026-07-14T16:34:03.633192+00:00",
                  "billingPeriodEnd": "2026-07-21T16:34:03.633192+00:00"
                }
              },
              "settings": {"future_field": ["ignored"]}
            }
        """.trimIndent()

        private val SETTINGS_RESPONSE = """
            {
              "subscription_tier_display": "SuperGrok Heavy"
            }
        """.trimIndent()
    }
}
