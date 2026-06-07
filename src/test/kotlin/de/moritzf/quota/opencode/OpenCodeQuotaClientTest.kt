package de.moritzf.quota.opencode

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Optional
import javax.net.ssl.SSLSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenCodeQuotaClientTest {

    @Test
    fun workspaceToStringIncludesNameIdMineAndGo() {
        val ws1 = OpenCodeWorkspace("wrk_a", "My Workspace", true, true)
        assertEquals("My Workspace (wrk_a) [mine] [Go]", ws1.toString())

        val ws2 = OpenCodeWorkspace("wrk_b", "", false, true)
        assertEquals("wrk_b [Go]", ws2.toString())

        val ws3 = OpenCodeWorkspace("wrk_c", "wrk_c", true, false)
        assertEquals("wrk_c [mine]", ws3.toString())
    }
    @Test
    fun parsesRealSolidStartResponse() {
        val body = ";0x00000126;((self.\$R=self.\$R||{})[\"server-fn:1\"]=[]," +
            "(\$R=>\$R[0]={mine:!0,useBalance:!1," +
            "rollingUsage:\$R[1]={status:\"ok\",resetInSec:17999,usagePercent:1}," +
            "weeklyUsage:\$R[2]={status:\"ok\",resetInSec:545090,usagePercent:6}," +
            "monthlyUsage:\$R[3]={status:\"ok\",resetInSec:2503851,usagePercent:24}})" +
            "(\$R[\"server-fn:1\"]))"

        val quota = OpenCodeQuotaClient.parseQuotaResponse(body)

        assertTrue(quota.mine)
        assertEquals(false, quota.useBalance)

        assertEquals(1, quota.rollingUsage?.usagePercent)
        assertEquals(17999L, quota.rollingUsage?.resetInSec)
        assertEquals("ok", quota.rollingUsage?.status)

        assertEquals(6, quota.weeklyUsage?.usagePercent)
        assertEquals(545090L, quota.weeklyUsage?.resetInSec)

        assertEquals(24, quota.monthlyUsage?.usagePercent)
        assertEquals(2503851L, quota.monthlyUsage?.resetInSec)
    }

    @Test
    fun parsesRateLimitedResponse() {
        val body = ";0x00000126;((self.\$R=self.\$R||{})[\"server-fn:1\"]=[]," +
            "(\$R=>\$R[0]={mine:!0,useBalance:!1," +
            "rollingUsage:\$R[1]={status:\"rate-limited\",resetInSec:3600,usagePercent:100}," +
            "weeklyUsage:\$R[2]={status:\"ok\",resetInSec:100000,usagePercent:50}," +
            "monthlyUsage:\$R[3]={status:\"ok\",resetInSec:2000000,usagePercent:30}})" +
            "(\$R[\"server-fn:1\"]))"

        val quota = OpenCodeQuotaClient.parseQuotaResponse(body)
        val rollingUsage = quota.rollingUsage!!

        assertTrue(rollingUsage.isRateLimited)
        assertEquals(100, rollingUsage.usagePercent)
        assertEquals(50, quota.weeklyUsage?.usagePercent)
    }

    @Test
    fun parsesStringsContainingBracesWithoutRegexRewrite() {
        val body = ";0x00000126;((self.\$R=self.\$R||{})[\"server-fn:1\"]=[]," +
            "(\$R=>\$R[0]={mine:!0,useBalance:!1," +
            "rollingUsage:\$R[1]={status:\"ok {still-json}\",resetInSec:17999,usagePercent:1}," +
            "weeklyUsage:\$R[2]={status:\"ok\",resetInSec:545090,usagePercent:6}," +
            "monthlyUsage:\$R[3]={status:\"ok\",resetInSec:2503851,usagePercent:24}})" +
            "(\$R[\"server-fn:1\"]))"

        val quota = OpenCodeQuotaClient.parseQuotaResponse(body)

        assertEquals("ok {still-json}", quota.rollingUsage?.status)
        assertEquals(1, quota.rollingUsage?.usagePercent)
    }

    @Test
    fun parsesNullResponse() {
        val body = ";0x00000001;((self.\$R=self.\$R||{})[\"server-fn:1\"]=[]," +
            "(\$R=>\$R[0]=null)(\$R[\"server-fn:1\"]))"

        try {
            OpenCodeQuotaClient.parseQuotaResponse(body)
            assertTrue(false, "Should have thrown for null response")
        } catch (e: OpenCodeQuotaException) {
            assertTrue(e.message?.contains("unexpected format") == true)
        }
    }

    @Test
    fun parsesBillingInfoResponse() {
        val body = ";0x00000040;((self.\$R=self.\$R||{})[\"server-fn:1\"]=[]," +
            "(\$R=>\$R[0]={balance:1234560000,customerID:\"cus_123\"})(\$R[\"server-fn:1\"]))"

        val billingInfo = OpenCodeQuotaClient.parseBillingInfoResponse(body)

        assertEquals(1234560000L, billingInfo.balance)
    }

    @Test
    fun balanceOnlyQuotaHasAvailableBalanceWithoutUsageState() {
        val quota = OpenCodeQuota(availableBalance = 1_234_560_000L, useBalance = true)

        assertTrue(quota.hasAvailableBalance())
        assertTrue(!quota.hasUsageState())
    }

    @Test
    fun fetchQuotaFallsBackToBillingForNullGoResponse() {
        val goResponse = ";0x0000002e;((self.\$R=self.\$R||{})[\"server-fn:1\"]=[],null)"
        val billingResponse = ";0x00000040;((self.\$R=self.\$R||{})[\"server-fn:1\"]=[]," +
            "(\$R=>\$R[0]={balance:1234560000,customerID:\"cus_123\"})(\$R[\"server-fn:1\"]))"
        val client = OpenCodeQuotaClient(
            httpClient = FakeHttpClient(
                "<script type=\"module\" src=\"/_build/assets/app.js\"></script>",
                "const queryLiteSubscription_query=createServerReference(\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\")",
                goResponse,
                billingResponse,
            ),
            endpoint = URI.create("https://opencode.test/_server"),
        )

        val quota = client.fetchQuota("session", "wrk_123")

        assertTrue(!quota.hasUsageState())
        assertEquals(1234560000L, quota.availableBalance)
        assertEquals(goResponse, quota.rawGoJson)
        assertEquals(billingResponse, quota.rawBillingJson)
        assertEquals(
            "OpenCode Go response:\n$goResponse\n\nOpenCode billing response:\n$billingResponse",
            quota.rawJson,
        )
    }

    @Test
    fun buildsCombinedRawResponseFromGoAndBillingBodies() {
        assertEquals(
            "OpenCode Go response:\ngo\n\nOpenCode billing response:\nbilling",
            OpenCodeQuotaClient.buildRawResponse("go", "billing"),
        )
        assertEquals("OpenCode Go response:\ngo", OpenCodeQuotaClient.buildRawResponse("go", null))
        assertEquals("OpenCode billing response:\nbilling", OpenCodeQuotaClient.buildRawResponse(null, "billing"))
    }

    private class FakeHttpClient(private vararg val bodies: String) : HttpClient() {
        private var index = 0

        override fun <T : Any?> send(request: HttpRequest, responseBodyHandler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
            @Suppress("UNCHECKED_CAST")
            return FakeResponse(bodies[index++]) as HttpResponse<T>
        }

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): java.util.concurrent.CompletableFuture<HttpResponse<T>> {
            throw UnsupportedOperationException()
        }

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
            pushPromiseHandler: HttpResponse.PushPromiseHandler<T>,
        ): java.util.concurrent.CompletableFuture<HttpResponse<T>> {
            throw UnsupportedOperationException()
        }

        override fun cookieHandler(): Optional<java.net.CookieHandler> = Optional.empty()
        override fun connectTimeout(): Optional<java.time.Duration> = Optional.empty()
        override fun followRedirects(): Redirect = Redirect.NEVER
        override fun proxy(): Optional<java.net.ProxySelector> = Optional.empty()
        override fun sslContext(): javax.net.ssl.SSLContext = javax.net.ssl.SSLContext.getDefault()
        override fun sslParameters(): javax.net.ssl.SSLParameters = javax.net.ssl.SSLParameters()
        override fun authenticator(): Optional<java.net.Authenticator> = Optional.empty()
        override fun version(): Version = Version.HTTP_1_1
        override fun executor(): Optional<java.util.concurrent.Executor> = Optional.empty()
    }

    private class FakeResponse(private val body: String) : HttpResponse<String> {
        override fun statusCode(): Int = 200
        override fun request(): HttpRequest? = null
        override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()
        override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }
        override fun body(): String = body
        override fun sslSession(): Optional<SSLSession> = Optional.empty()
        override fun uri(): URI = URI.create("https://opencode.test/_server")
        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
    }
}
