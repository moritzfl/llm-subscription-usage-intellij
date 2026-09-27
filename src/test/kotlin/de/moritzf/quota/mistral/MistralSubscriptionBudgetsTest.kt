package de.moritzf.quota.mistral

import de.moritzf.quota.idea.ui.indicator.mistralBarDisplayText
import de.moritzf.quota.shared.JsonSupport
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.serialization.json.JsonPrimitive

class MistralSubscriptionBudgetsTest {
    @Test
    fun fetchReadsAllowancesWithPluginSessionAndKeepsProviderBudgetJson() {
        val http = FakeHttpClient(mapOf(
            "/api/billing/v2/usage" to (200 to """{"vibe_usage":0}"""),
            "/api-ui/trpc/billing.vibeUsage" to (200 to """[{"result":{"data":{"json":{"usage_percentage":0}}}}]"""),
            "/subscription" to (200 to flight("a:{\"budget\":$BUDGETS}\n")),
        ))
        val quota = MistralQuotaClient(http) { Instant.parse("2026-09-28T10:00:00Z") }
            .fetchQuota("ory_session_test=test-token; csrftoken=test-csrf")
        assertEquals(12.75, quota.includedApiUsage?.limitAmount)
        assertEquals(127.5, quota.monthlyUsage?.limitAmount)
        assertTrue(mistralBarDisplayText(quota, null).startsWith("93%"))
        val request = http.requests.single { it.uri().path == "/subscription" }
        assertEquals("https://admin.mistral.ai/subscription", request.uri().toString())
        assertEquals("text/html", request.headers().firstValue("Accept").orElse(null))
        assertTrue(request.headers().firstValue("Cookie").orElse("").contains("ory_session_test=test-token"))
        assertEquals("month=9&year=2026", http.requests.first().uri().query)
        assertTrue(quota.rawJson!!.contains("\"api_budget\""))
        assertTrue(!quota.rawJson!!.contains("test-token"))
        assertTrue(!quota.rawJson!!.contains("<script>"))
    }

    @Test
    fun subscriptionStillWorksWhenLegacyEndpointsFail() {
        val http = FakeHttpClient(mapOf("/subscription" to (200 to flight("a:{\"budget\":$BUDGETS}\n"))))
        val quota = MistralQuotaClient(http).fetchQuota("ory_session_test=test-token; csrftoken=test-csrf")
        assertEquals(12.75, quota.includedApiUsage?.limitAmount)
        assertEquals(127.5, quota.monthlyUsage?.limitAmount)
        assertTrue(quota.rawJson!!.contains("billing_error"))
    }

    @Test
    fun missingSubscriptionPagePreservesLegacyReadingWithoutInventingAllowance() {
        val http = FakeHttpClient(mapOf("/api/billing/v2/usage" to (200 to """{"vibe_usage":12}""")))
        val quota = MistralQuotaClient(http).fetchQuota("ory_session_test=test-token")
        assertEquals(12.0, quota.monthlyUsage?.usagePercent)
        assertNull(quota.includedApiUsage)
        assertTrue(quota.rawJson!!.contains("subscription_error"))
    }

    @Test
    fun readsSeparateAllowancesAcrossFlightChunksAndUsesApiPercentInsteadOfZeroVibe() {
        val stream = "a:[\"component\",{\"budget\":$BUDGETS}]\n"
        val html = flight(stream.take(70)) + flight(stream.drop(70))
        val budgets = assertNotNull(MistralSubscriptionBudgets.parse(html))
        val api = assertNotNull(MistralSubscriptionBudgets.window(budgets["api_budget"]))
        val vibe = assertNotNull(MistralSubscriptionBudgets.window(budgets["vibe_budget"]))
        assertEquals(93.25490196078431, api.usagePercent)
        assertEquals(11.89, api.usedAmount!!, 1e-10)
        assertEquals(12.75, api.limitAmount)
        assertEquals("EUR", api.currency)
        assertEquals(Instant.parse("2026-10-01T00:00:00Z"), api.resetsAt)
        assertEquals(30L * 86_400_000, api.periodDurationMs)
        assertEquals(0.0, vibe.usagePercent)
        assertEquals(127.5, vibe.limitAmount)

        val quota = MistralQuota(monthlyUsage = vibe, includedApiUsage = api)
        assertSame(api, quota.displayWindow())
        assertEquals(api.usagePercent / 100, quota.usageFraction())
        assertEquals(api.usagePercent / 100, quota.activityWindows()["includedApi"])
        assertTrue(mistralBarDisplayText(quota, null).startsWith("93%"))
        val saved = JsonSupport.json.encodeToString(MistralQuota.serializer(), quota)
        assertEquals(quota, JsonSupport.json.decodeFromString<MistralQuota>(saved))
    }

    @Test
    fun zeroAllowanceUsageStaysAPercentageEvenWhenApiActivityExists() {
        val api = MistralUsageWindow(usagePercent = 0.0, limitAmount = 12.75, usedAmount = 0.0, currency = "EUR")
        val quota = MistralQuota(includedApiUsage = api, apiUsage = MistralApiUsage(spendEur = 0.01, tokens = 100))
        assertEquals("0%", mistralBarDisplayText(quota, null))
        val busyVibe = MistralUsageWindow(usagePercent = 80.0)
        assertSame(busyVibe, quota.copy(monthlyUsage = busyVibe).displayWindow())
    }

    @Test
    fun skipsByteCountedTextWithUnicodeAndFakeBudgetRecords() {
        val fake = "€\na:{\"budget\":${BUDGETS.replace("12.75", "999.0")}}\n"
        val stream = "1:T${fake.toByteArray().size.toString(16)},$fake" + "2:{\"budget\":$BUDGETS}\n"
        val budgets = assertNotNull(MistralSubscriptionBudgets.parse(flight(stream)))
        assertEquals(12.75, MistralSubscriptionBudgets.window(budgets["api_budget"])?.limitAmount)
    }

    @Test
    fun rejectsAmbiguousOrTruncatedRecordsAndLoginPages() {
        val one = "a:{\"budget\":$BUDGETS}\n"
        val other = "b:{\"budget\":${BUDGETS.replace("12.75", "999.0")}}\n"
        assertNull(MistralSubscriptionBudgets.parse(flight(one + other)))
        assertNull(MistralSubscriptionBudgets.parse(flight("1:Tffff,short")))
        assertNull(MistralSubscriptionBudgets.parse("<html>Sign in</html>"))
        assertNull(MistralSubscriptionBudgets.parse(flight("a:{\"budget\":")))
        assertNotNull(MistralSubscriptionBudgets.parse(flight(one + one)))
    }

    @Test
    fun validatesAmountsAndSupportsOverageAndCalendarMonthReset() {
        fun window(percent: String, limit: String, currency: String = "USD") =
            MistralSubscriptionBudgets.window(JsonSupport.json.parseToJsonElement(
                """{"usage_percentage":$percent,"initial_budget":$limit,"currency":"$currency","reset_at":"2026-03-01T00:00:00Z"}""",
            ))
        assertNull(window("-1", "15"))
        assertNull(window("0", "0"))
        assertNull(window("\"NaN\"", "15"))
        assertNull(window("5", "1e999"))
        assertNull(window("5", "15", "bad currency"))
        val overage = assertNotNull(window("110", "15"))
        assertEquals(110.0, overage.usagePercent)
        assertEquals(16.5, overage.usedAmount)
        assertEquals(28L * 86_400_000, overage.periodDurationMs)
    }

    @Test
    fun rawResponseIncludesOnlyExtractedProviderBudgetJson() {
        val budgets = assertNotNull(MistralSubscriptionBudgets.parse(flight("a:{\"budget\":$BUDGETS}\n")))
        val raw = MistralQuotaClient.buildRawResponse(null, null, null, null, subscriptionBudgets = budgets)
        assertTrue(raw.contains("\"subscription\""))
        assertTrue(raw.contains("\"api_budget\""))
        assertTrue(!raw.contains("__next_f"))
    }

    private fun flight(stream: String): String = "<script>self.__next_f.push([1,${JsonPrimitive(stream)}])</script>"

    private class FakeHttpClient(private val responses: Map<String, Pair<Int, String>>) : HttpClient() {
        val requests = mutableListOf<HttpRequest>()

        override fun <T : Any?> send(request: HttpRequest, responseBodyHandler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
            requests += request
            val (status, body) = responses[request.uri().path] ?: (500 to "unavailable")
            val response = object : HttpResponse<String> {
                override fun statusCode(): Int = status
                override fun request(): HttpRequest = request
                override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()
                override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }
                override fun body(): String = body
                override fun sslSession(): Optional<SSLSession> = Optional.empty()
                override fun uri(): URI = request.uri()
                override fun version(): Version = Version.HTTP_1_1
            }
            @Suppress("UNCHECKED_CAST")
            return response as HttpResponse<T>
        }

        override fun <T : Any?> sendAsync(request: HttpRequest, responseBodyHandler: HttpResponse.BodyHandler<T>): CompletableFuture<HttpResponse<T>> =
            throw UnsupportedOperationException()
        override fun <T : Any?> sendAsync(request: HttpRequest, responseBodyHandler: HttpResponse.BodyHandler<T>, pushPromiseHandler: HttpResponse.PushPromiseHandler<T>): CompletableFuture<HttpResponse<T>> =
            throw UnsupportedOperationException()
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

    companion object {
        private const val BUDGETS = """{"api_budget":{"usage_percentage":93.25490196078431,"initial_budget":12.75,"currency":"EUR","reset_at":"2026-10-01T00:00:00Z"},"vibe_budget":{"usage_percentage":0,"initial_budget":127.5,"currency":"EUR","reset_at":"2026-10-01T00:00:00Z"}}"""
    }
}
