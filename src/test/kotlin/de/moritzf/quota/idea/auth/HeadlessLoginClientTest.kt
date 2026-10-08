package de.moritzf.quota.idea.auth

import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.shared.JsonSupport
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.Base64
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Flow
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeadlessLoginClientTest {
    @Test
    fun codexExchangesDeviceCodeWithProviderPkceAndDeviceRedirect() = runBlocking {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"https://api.openai.com/auth":{"chatgpt_account_id":"workspace"}}""".toByteArray(),
        )
        val http = FakeHttp(
            200 to """{"device_auth_id":"private-device","user_code":"ABCD-EFGH","interval":"5","expires_at":"2030-01-01T00:10:00+00:00"}""",
            403 to "pending",
            404 to "pending",
            200 to """{"authorization_code":"auth-code","code_verifier":"provider-verifier","code_challenge":"provider-challenge"}""",
            200 to """{"access_token":"access","refresh_token":"refresh","id_token":"header.$payload.sig","expires_in":3600}""",
        )
        val client = deviceClient(http, QuotaProviderType.OPEN_AI)
        val authorization = client.requestAuthorization()
        assertEquals("https://auth.openai.com/codex/device", authorization.prompt.verificationUrl)
        assertEquals("ABCD-EFGH", authorization.prompt.userCode)
        assertEquals(NOW + 600_000, authorization.prompt.expiresAtMs)
        assertEquals(5, authorization.intervalSeconds)
        assertEquals(OAuthDevicePollResult.Pending, client.poll(authorization))
        assertEquals(OAuthDevicePollResult.Pending, client.poll(authorization))
        val credentials = assertIs<OAuthDevicePollResult.Authorized>(client.poll(authorization)).credentials
        assertEquals("access", credentials.accessToken)
        assertEquals("refresh", credentials.refreshToken)
        assertEquals("workspace", credentials.accountId)
        assertFalse(credentials.personalAccessToken)
        assertEquals("https://auth.openai.com/api/accounts/deviceauth/usercode", http.requests.first().uri().toString())
        assertEquals(
            JsonSupport.json.parseToJsonElement("""{"client_id":"app_EMoamEEZ73f0CkXaXp7hrann"}"""),
            JsonSupport.json.parseToJsonElement(http.body(0)),
        )
        assertEquals(
            JsonSupport.json.parseToJsonElement("""{"device_auth_id":"private-device","user_code":"ABCD-EFGH"}"""),
            JsonSupport.json.parseToJsonElement(http.body(1)),
        )
        val exchange = OAuthUrlCodec.parseQuery(http.body(4))
        assertEquals("authorization_code", exchange["grant_type"])
        assertEquals("provider-verifier", exchange["code_verifier"])
        assertEquals("auth-code", exchange["code"])
        assertEquals("https://auth.openai.com/deviceauth/callback", exchange["redirect_uri"])
        assertEquals("https://auth.openai.com/oauth/token", http.requests.last().uri().toString())
        assertTrue(http.requests.all { it.method() == "POST" })
    }

    @Test
    fun codexAcceptsLegacyUsercodeAndIntervalFormatsWithBoundedDefaultExpiry() = runBlocking {
        for (interval in listOf("7", "\" 7 \"")) {
            val client = deviceClient(FakeHttp(200 to """{"device_auth_id":"private","usercode":"CODE","interval":$interval}"""), QuotaProviderType.OPEN_AI)
            val authorization = client.requestAuthorization()
            assertEquals("CODE", authorization.prompt.userCode)
            assertEquals(7, authorization.intervalSeconds)
            assertEquals(NOW + 900_000, authorization.prompt.expiresAtMs)
        }
    }

    @Test
    fun xaiUsesStandardDeviceGrantAndProviderPollingErrors() = runBlocking {
        val http = FakeHttp(
            200 to XAI_AUTHORIZATION,
            400 to """{"error":"authorization_pending"}""",
            400 to """{"error":"slow_down"}""",
            200 to """{"access_token":"xai-access","refresh_token":"xai-refresh","expires_in":3600}""",
            400 to """{"error":"expired_token"}""",
            400 to """{"error":"access_denied"}""",
        )
        val client = deviceClient(http, QuotaProviderType.SUPERGROK)
        val authorization = client.requestAuthorization()
        assertEquals("https://accounts.x.ai/oauth2/device", authorization.prompt.verificationUrl)
        assertEquals("GROK-CODE", authorization.prompt.userCode)
        assertEquals(NOW + 1_800_000, authorization.prompt.expiresAtMs)
        assertEquals(5, authorization.intervalSeconds)
        assertEquals(OAuthDevicePollResult.Pending, client.poll(authorization))
        assertEquals(OAuthDevicePollResult.SlowDown, client.poll(authorization))
        assertEquals("xai-refresh", assertIs<OAuthDevicePollResult.Authorized>(client.poll(authorization)).credentials.refreshToken)
        assertTrue(assertFailsWith<IOException> { client.poll(authorization) }.message!!.contains("expired"))
        assertTrue(assertFailsWith<IOException> { client.poll(authorization) }.message!!.contains("denied"))
        val request = OAuthUrlCodec.parseQuery(http.body(0))
        assertEquals(OAuthClientConfig.xAiGrokDefaults().scopes, request["scope"])
        assertEquals("https://auth.x.ai/oauth2/device/code", http.requests.first().uri().toString())
        val poll = OAuthUrlCodec.parseQuery(http.body(1))
        assertEquals("urn:ietf:params:oauth:grant-type:device_code", poll["grant_type"])
        assertEquals("private-xai-device", poll["device_code"])
        assertEquals(OAuthClientConfig.xAiGrokDefaults().clientId, poll["client_id"])
        assertEquals("application/x-www-form-urlencoded", http.requests[1].headers().firstValue("Content-Type").orElseThrow())
    }

    @Test
    fun malformedOrUnusableDeviceResponsesNeverExposeGrantSecrets() = runBlocking {
        for (body in listOf(
            """{"device_auth_id":"secret-device","user_code":"CODE","interval":{}}""",
            """{"device_auth_id":"secret-device"}""",
            """{"device_auth_id":"secret-device","user_code":"CODE","expires_at":"2020-01-01T00:00:00Z"}""",
        )) {
            val failure = assertFailsWith<IOException> { deviceClient(FakeHttp(200 to body), QuotaProviderType.OPEN_AI).requestAuthorization() }
            assertFalse(failure.stackTraceToString().contains("secret-device"))
        }
        val invalidUrl = XAI_AUTHORIZATION.replace("https://accounts.x.ai/oauth2/device", "javascript:alert(1)")
        assertFailsWith<IOException> { deviceClient(FakeHttp(200 to invalidUrl), QuotaProviderType.SUPERGROK).requestAuthorization() }
        val failure = assertFailsWith<IOException> { deviceClient(FakeHttp(404 to "secret"), QuotaProviderType.OPEN_AI).requestAuthorization() }
        assertTrue(failure.message!!.contains("security settings"))
        assertFalse(failure.message!!.contains("secret"))
    }

    @Test
    fun personalTokenIsValidatedUsingWhoamiAndRetainsWorkspaceIdentity() = runBlocking {
        val http = FakeHttp(200 to """{"chatgpt_account_id":"workspace","chatgpt_account_is_fedramp":false,"chatgpt_plan_type":"business"}""")
        val credentials = OpenAiPersonalTokenClient(http).validate("  at-personal-token  ")
        assertEquals("at-personal-token", credentials.accessToken)
        assertEquals("workspace", credentials.accountId)
        assertTrue(credentials.personalAccessToken)
        assertNull(credentials.refreshToken)
        assertEquals(Long.MAX_VALUE, credentials.expiresAt)
        assertEquals("https://auth.openai.com/api/accounts/v1/user-auth-credential/whoami", http.requests.single().uri().toString())
        assertEquals("GET", http.requests.single().method())
        assertEquals("Bearer at-personal-token", http.requests.single().headers().firstValue("Authorization").orElseThrow())
        val restored = JsonSupport.json.decodeFromString<OAuthCredentials>(JsonSupport.json.encodeToString(credentials))
        assertTrue(restored.personalAccessToken)
        assertEquals(Long.MAX_VALUE, restored.expiresAt)
    }

    @Test
    fun personalTokenRejectsUnsupportedInputsBeforeSending() = runBlocking {
        val http = FakeHttp()
        for (token in listOf("", "sk-platform-key", "header.payload.signature", "at-", "at-secret\nextra", "at-secret\u0000extra")) {
            val failure = assertFailsWith<IllegalArgumentException> { OpenAiPersonalTokenClient(http).validate(token) }
            assertFalse(failure.stackTraceToString().contains("at-secret"))
        }
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun personalTokenRejectsInvalidOrUnsupportedAccountsWithoutLeakingResponse() = runBlocking {
        for ((status, body) in listOf(
            401 to "at-secret-token rejected",
            200 to """{"access_token":"at-secret-token","chatgpt_account_id":{}}""",
            200 to """{"chatgpt_account_id":""}""",
            200 to """{"chatgpt_account_id":"workspace","chatgpt_account_is_fedramp":true}""",
        )) {
            val failure = assertFailsWith<IOException> { OpenAiPersonalTokenClient(FakeHttp(status to body)).validate("at-secret-token") }
            assertFalse(failure.stackTraceToString().contains("at-secret-token"))
        }
    }

    private fun deviceClient(http: HttpClient, type: QuotaProviderType) =
        OAuthDeviceLoginClient(http, type, OAuthClientConfig.forProvider(type), nowMs = { NOW })

    private class FakeHttp(vararg responses: Pair<Int, String>) : HttpClient() {
        private val responses = ArrayDeque(responses.toList())
        val requests = mutableListOf<HttpRequest>()

        fun body(index: Int): String {
            val subscriber = HttpResponse.BodySubscribers.ofString(Charsets.UTF_8)
            requests[index].bodyPublisher().orElseThrow().subscribe(object : Flow.Subscriber<ByteBuffer> {
                override fun onSubscribe(subscription: Flow.Subscription) = subscriber.onSubscribe(subscription)
                override fun onNext(item: ByteBuffer) = subscriber.onNext(listOf(item))
                override fun onError(throwable: Throwable) = subscriber.onError(throwable)
                override fun onComplete() = subscriber.onComplete()
            })
            return subscriber.body.toCompletableFuture().join()
        }

        override fun <T : Any?> send(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
            requests += request
            val (status, body) = responses.removeFirst()
            @Suppress("UNCHECKED_CAST")
            return Response(request, status, body) as HttpResponse<T>
        }

        override fun <T : Any?> sendAsync(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): CompletableFuture<HttpResponse<T>> = error("Unexpected async request")
        override fun <T : Any?> sendAsync(request: HttpRequest, handler: HttpResponse.BodyHandler<T>, push: HttpResponse.PushPromiseHandler<T>): CompletableFuture<HttpResponse<T>> = error("Unexpected async request")
        override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()
        override fun connectTimeout(): Optional<Duration> = Optional.empty()
        override fun followRedirects() = Redirect.NEVER
        override fun proxy(): Optional<ProxySelector> = Optional.empty()
        override fun sslContext(): SSLContext = SSLContext.getDefault()
        override fun sslParameters() = SSLParameters()
        override fun authenticator(): Optional<Authenticator> = Optional.empty()
        override fun version() = Version.HTTP_1_1
        override fun executor(): Optional<Executor> = Optional.empty()
    }

    private class Response(private val request: HttpRequest, private val status: Int, private val body: String) : HttpResponse<String> {
        override fun statusCode() = status
        override fun request() = request
        override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()
        override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }
        override fun body() = body
        override fun sslSession(): Optional<SSLSession> = Optional.empty()
        override fun uri(): URI = request.uri()
        override fun version() = HttpClient.Version.HTTP_1_1
    }

    companion object {
        private const val NOW = 1_893_456_000_000L // 2030-01-01T00:00:00Z
        private const val XAI_AUTHORIZATION = """{"device_code":"private-xai-device","user_code":"GROK-CODE","verification_uri":"https://accounts.x.ai/oauth2/device","verification_uri_complete":"https://accounts.x.ai/oauth2/device?user_code=GROK-CODE","expires_in":1800,"interval":5}"""
    }
}
