package de.moritzf.quota.supergrok

import kotlinx.datetime.Instant
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

class SuperGrokQuotaClientTest {
    @Test
    fun parseQuotaExtractsBillingCreditsAndPlan() {
        val fetchedAt = Instant.parse("2026-06-16T12:00:00Z")
        val quota = SuperGrokQuotaClient.parseQuota(
            billingJson = BILLING_RESPONSE,
            settingsJson = SETTINGS_RESPONSE,
            fetchedAt = fetchedAt,
        )

        assertEquals("SuperGrok Heavy", quota.plan)
        assertEquals("xai-oauth-cli-proxy", quota.authSource)
        assertEquals(fetchedAt, quota.fetchedAt)
        assertEquals(120_000L, quota.onDemandCap)
        val usage = quota.creditUsage ?: error("credit usage missing")
        assertEquals("Credits used", usage.label)
        assertEquals(2_500L, usage.used)
        assertEquals(10_000L, usage.limit)
        assertEquals(25.0, usage.usagePercent)
        assertEquals(Instant.parse("2026-07-01T00:00:00Z"), usage.resetsAt)
        assertEquals(2_592_000_000L, usage.periodDurationMs)
    }

    @Test
    fun parseQuotaReportsChangedBillingPayload() {
        val exception = assertFailsWith<SuperGrokQuotaException> {
            SuperGrokQuotaClient.parseQuota(billingJson = """{"ok":true}""")
        }

        assertEquals("Grok billing response changed.", exception.message)
    }

    @Test
    fun fetchQuotaUsesCliProxyBillingAndSettingsEndpoints() {
        val httpClient = FakeHttpClient(
            FakeResponseSpec(BILLING_RESPONSE),
            FakeResponseSpec(SETTINGS_RESPONSE),
        )
        val client = SuperGrokQuotaClient(httpClient, URI.create("https://grok.test/v1/"))

        val quota = client.fetchQuota("token-123")

        assertEquals("SuperGrok Heavy", quota.plan)
        assertEquals(listOf("https://grok.test/v1/billing", "https://grok.test/v1/settings"), httpClient.requests.map { it.uri().toString() })
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
        val httpClient = FakeHttpClient(FakeResponseSpec(BILLING_RESPONSE))
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

    @Test
    fun fetchQuotaRetriesGrokBillingTimeout() {
        val httpClient = FakeHttpClient(
            FakeResponseSpec(BILLING_TIMEOUT_RESPONSE, status = 400),
            FakeResponseSpec(BILLING_RESPONSE),
            FakeResponseSpec(SETTINGS_RESPONSE),
        )
        val client = SuperGrokQuotaClient(httpClient, URI.create("https://grok.test/v1/"))

        val quota = client.fetchQuota("token-123")

        assertEquals("SuperGrok Heavy", quota.plan)
        assertEquals(
            listOf(
                "https://grok.test/v1/billing",
                "https://grok.test/v1/billing",
                "https://grok.test/v1/settings",
            ),
            httpClient.requests.map { it.uri().toString() },
        )
    }

    @Test
    fun fetchQuotaReportsGrokBillingTimeoutAfterRetry() {
        val httpClient = FakeHttpClient(
            FakeResponseSpec(BILLING_TIMEOUT_RESPONSE, status = 400),
            FakeResponseSpec(BILLING_TIMEOUT_RESPONSE, status = 400),
        )
        val client = SuperGrokQuotaClient(httpClient, URI.create("https://grok.test/v1/"))

        val exception = assertFailsWith<SuperGrokQuotaException> {
            client.fetchQuota("token-123")
        }

        assertEquals(
            "Grok billing request timed out. The Grok billing API cancelled the request before returning usage data; try again later.",
            exception.message,
        )
        assertEquals(400, exception.statusCode)
        assertEquals(null, exception.rawBody)
        assertEquals(2, httpClient.requests.size)
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

    private companion object {
        private val BILLING_RESPONSE = """
            {
              "config": {
                "monthlyLimit": {"val": 10000},
                "used": {"val": 2500},
                "onDemandCap": {"val": 120000},
                "billingPeriodStart": "2026-06-01T00:00:00Z",
                "billingPeriodEnd": "2026-07-01T00:00:00Z"
              }
            }
        """.trimIndent()

        private val SETTINGS_RESPONSE = """
            {
              "subscription_tier_display": "SuperGrok Heavy"
            }
        """.trimIndent()

        private const val BILLING_TIMEOUT_RESPONSE = """{"code":"The operation was cancelled","error":"Timeout expired"}"""
    }
}
