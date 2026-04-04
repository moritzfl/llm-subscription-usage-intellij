package de.moritzf.quota

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.intellij.lang.annotations.Language
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import kotlin.test.*

class OpenAiCodexQuotaClientTest {
    @Test
    fun customDeserializationMapsTopLevelAndWindowFields() {
        @Language("JSON")
        val json = """
            {
              "user_id": "user-1",
              "account_id": "account-1",
              "email": "user@example.com",
              "plan_type": "pro",
              "rate_limit": {
                "allowed": true,
                "limit_reached": false,
                "primary_window": {
                  "used_percent": 12.3,
                  "limit_window_seconds": 18000,
                  "reset_at": 1735689600
                },
                "secondary_window": {
                  "used_percent": 45.6,
                  "limit_window_seconds": 604800,
                  "reset_at": 1736294400
                }
              }
            }
        """.trimIndent()

        val quota = deserializeQuota(json)

        assertEquals("pro", quota.planType)
        assertEquals("account-1", quota.accountId)
        assertEquals("user@example.com", quota.email)
        assertEquals(true, quota.allowed)
        assertEquals(false, quota.limitReached)

        assertNotNull(quota.primary)
        assertEquals(12.3, quota.primary!!.usedPercent, 0.0001)
        assertEquals(Duration.ofMinutes(300), quota.primary!!.windowDuration)
        assertEquals(Instant.fromEpochSeconds(1735689600), quota.primary!!.resetsAt)

        assertNotNull(quota.secondary)
        assertEquals(45.6, quota.secondary!!.usedPercent, 0.0001)
        assertEquals(Duration.ofMinutes(10080), quota.secondary!!.windowDuration)
        assertEquals(Instant.fromEpochSeconds(1736294400), quota.secondary!!.resetsAt)
    }

    @Test
    fun customDeserializationClampsPercentValuesAndAllowsMissingOptionalWindowFields() {
        @Language("JSON")
        val json = """
            {
              "rate_limit": {
                "primary_window": { "used_percent": -5.0 },
                "secondary_window": { "used_percent": 101.0 }
              }
            }
        """.trimIndent()

        val quota = deserializeQuota(json)

        assertNotNull(quota.primary)
        assertEquals(0.0, quota.primary!!.usedPercent, 0.0)
        assertNull(quota.primary!!.windowDuration)
        assertNull(quota.primary!!.resetsAt)

        assertNotNull(quota.secondary)
        assertEquals(100.0, quota.secondary!!.usedPercent, 0.0)
        assertNull(quota.secondary!!.windowDuration)
        assertNull(quota.secondary!!.resetsAt)
    }

    @Test
    fun fetchQuotaThrowsWhenNoUsableWindowsArePresent() {
        @Language("JSON")
        val json = """
            {
              "rate_limit": {
                "allowed": true,
                "limit_reached": false
              }
            }
        """.trimIndent()

        val client = newClientReturning(200, json)
        val exception = assertFailsWith<OpenAiCodexQuotaException> {
            client.fetchQuota("token", "account-1")
        }

        assertEquals(200, exception.statusCode)
        assertTrue(exception.message.orEmpty().contains("did not include usable windows"))
    }

    @Test
    fun fetchQuotaAddsClientMetadata() {
        val before = Clock.System.now()
        @Language("JSON")
        val json = """
            {
              "rate_limit": {
                "primary_window": {
                  "used_percent": 12.3,
                  "limit_window_seconds": 18000,
                  "reset_at": 1735689600
                }
              }
            }
        """.trimIndent()

        val client = newClientReturning(200, json)
        val quota = client.fetchQuota("token", "account-1")
        val after = Clock.System.now()

        assertEquals(json, quota.rawJson)
        assertNotNull(quota.fetchedAt)
        assertTrue(quota.fetchedAt!! >= before)
        assertTrue(quota.fetchedAt!! <= after)
    }

    @Test
    fun customDeserializationMapsCodeReviewRateLimitFromAnonymizedPayload() {
        @Language("JSON")
        val json = """
            {
              "user_id": "user-anon-1",
              "account_id": "account-anon-1",
              "email": "user@example.com",
              "plan_type": "go",
              "rate_limit": {
                "allowed": true,
                "limit_reached": false,
                "primary_window": {
                  "used_percent": 33,
                  "limit_window_seconds": 604800,
                  "reset_after_seconds": 454749,
                  "reset_at": 1773936760
                },
                "secondary_window": null
              },
              "code_review_rate_limit": {
                "allowed": true,
                "limit_reached": false,
                "primary_window": {
                  "used_percent": 0,
                  "limit_window_seconds": 604800,
                  "reset_after_seconds": 604800,
                  "reset_at": 1774086811
                },
                "secondary_window": null
              },
              "additional_rate_limits": null,
              "credits": null,
              "promo": null
            }
        """.trimIndent()

        val quota = deserializeQuota(json)

        assertEquals("go", quota.planType)
        assertEquals(true, quota.allowed)
        assertEquals(false, quota.limitReached)
        assertEquals(true, quota.reviewAllowed)
        assertEquals(false, quota.reviewLimitReached)

        assertNotNull(quota.primary)
        assertEquals(33.0, quota.primary!!.usedPercent, 0.0)
        assertEquals(Duration.ofMinutes(10080), quota.primary!!.windowDuration)
        assertEquals(Instant.fromEpochSeconds(1773936760), quota.primary!!.resetsAt)

        assertNotNull(quota.reviewPrimary)
        assertEquals(0.0, quota.reviewPrimary!!.usedPercent, 0.0)
        assertEquals(Duration.ofMinutes(10080), quota.reviewPrimary!!.windowDuration)
        assertEquals(Instant.fromEpochSeconds(1774086811), quota.reviewPrimary!!.resetsAt)

        assertNull(quota.reviewSecondary)
    }

    private fun deserializeQuota(@Language("JSON") json: String): OpenAiCodexQuota {
        return JsonSupport.json.decodeFromString(json)
    }

    private fun newClientReturning(statusCode: Int, @Language("JSON") body: String): OpenAiCodexQuotaClient {
        return OpenAiCodexQuotaClient(StubHttpClient(statusCode, body), URI.create("https://example.com/usage"))
    }

    private class StubHttpClient(private val statusCode: Int, private val body: String) : HttpClient() {
        override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()

        override fun connectTimeout(): Optional<Duration> = Optional.empty()

        override fun followRedirects(): Redirect = Redirect.NEVER

        override fun proxy(): Optional<ProxySelector> = Optional.empty()

        override fun sslContext(): SSLContext? = null

        override fun sslParameters(): SSLParameters = SSLParameters()

        override fun authenticator(): Optional<Authenticator> = Optional.empty()

        override fun version(): Version = Version.HTTP_1_1

        override fun executor(): Optional<Executor> = Optional.empty()

        override fun <T> send(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>
        ): HttpResponse<T> {
            @Suppress("UNCHECKED_CAST")
            return StubHttpResponse(request, statusCode, body) as HttpResponse<T>
        }

        override fun <T> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): CompletableFuture<HttpResponse<T>> {
            return CompletableFuture.completedFuture(send(request, responseBodyHandler))
        }

        override fun <T> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
            pushPromiseHandler: HttpResponse.PushPromiseHandler<T>,
        ): CompletableFuture<HttpResponse<T>> {
            return CompletableFuture.completedFuture(send(request, responseBodyHandler))
        }
    }

    private data class StubHttpResponse(
        private val request: HttpRequest,
        private val responseStatusCode: Int,
        private val responseBody: String,
    ) : HttpResponse<String> {
        override fun statusCode(): Int = responseStatusCode

        override fun request(): HttpRequest = request

        override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()

        override fun headers(): HttpHeaders = HttpHeaders.of(mapOf()) { _, _ -> true }

        override fun body(): String = responseBody

        override fun sslSession(): Optional<SSLSession> = Optional.empty()

        override fun uri(): URI = request.uri()

        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
    }
}
