package de.moritzf.quota.kimi

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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class KimiRegionTest {
    @Test
    fun loginsWithoutARegionAreMainlandLogins() {
        assertEquals(KimiRegion.MAINLAND, KimiCredentials(accessToken = "a").kimiRegion)
        assertEquals(KimiRegion.MAINLAND, KimiRegion.of("unknown"))
        assertEquals(KimiRegion.GLOBAL, KimiCredentials(region = "global").kimiRegion)
        assertEquals(
            URI.create("https://api.kimi.ai/coding/v1/usages"),
            KimiRegion.GLOBAL.api("usages"),
        )
        assertEquals(
            URI.create("https://auth.kimi.com/api/oauth/token"),
            KimiRegion.MAINLAND.tokenEndpoint,
        )
        assertEquals(URI.create("https://api.kimi.ai/coding"), KimiRegion.GLOBAL.anthropicBase)
    }

    @Test
    fun theRegionSurvivesSerializationAndOldLoginsStayReadable() {
        val global = KimiCredentials(accessToken = "a", refreshToken = "r", region = "global")
        val encoded = JsonSupport.json.encodeToString(KimiCredentials.serializer(), global)

        assertEquals(
            global,
            JsonSupport.json.decodeFromString(KimiCredentials.serializer(), encoded),
        )
        assertEquals(
            KimiRegion.MAINLAND,
            JsonSupport.json
                .decodeFromString(
                    KimiCredentials.serializer(),
                    """{"accessToken":"a","refreshToken":"r"}""",
                )
                .kimiRegion,
        )
    }

    @Test
    fun globalLoginsRefreshAndReadUsageOnKimiAi() {
        val http = RecordingHttpClient { request ->
            when (request.uri().path) {
                "/api/oauth/token" ->
                    200 to """{"access_token":"fresh","refresh_token":"r2","expires_in":3600}"""
                else -> 200 to """{"usages":{"limit_5h":{"used_ratio":0.5}}}"""
            }
        }
        val result =
            KimiQuotaClient(httpClient = http, deviceId = "device")
                .fetchQuota(
                    KimiCredentials(
                        accessToken = "stale",
                        refreshToken = "r1",
                        expiresAtEpochSeconds = 0.0,
                        region = "global",
                    )
                )

        assertEquals(
            listOf(
                "https://auth.kimi.ai/api/oauth/token",
                "https://api.kimi.ai/coding/v1/usages",
            ),
            http.requests.map { it.uri().toString() },
        )
        assertEquals("global", result.credentials.region, "a refreshed login keeps its region")
        assertEquals(50.0, result.quota.sessionUsage?.usagePercent)
    }

    @Test
    fun mainlandLoginsKeepUsingKimiCom() {
        val http = RecordingHttpClient { 200 to """{"usages":{}}""" }
        KimiQuotaClient(httpClient = http).fetchQuota(KimiCredentials(accessToken = "token"))
        KimiWebSearchClient(httpClient = http)
            .webFetch(KimiCredentials(accessToken = "token"), "example.com")

        assertEquals(
            listOf("https://api.kimi.com/coding/v1/usages", "https://api.kimi.com/coding/v1/fetch"),
            http.requests.map { it.uri().toString() },
        )
    }

    @Test
    fun deviceLoginOnKimiAiSavesAGlobalLogin() {
        val http = RecordingHttpClient { request ->
            if (request.uri().path.endsWith("device_authorization"))
                200 to
                    """{"device_code":"dc","user_code":"UC","verification_uri_complete":"https://www.kimi.ai/code/authorize_device?user_code=UC","expires_in":1800,"interval":5}"""
            else 200 to """{"access_token":"a","refresh_token":"r","expires_in":3600}"""
        }
        val client = KimiOAuthClient(httpClient = http, deviceId = "device")

        val authorization = client.requestDeviceAuthorization(KimiRegion.GLOBAL)
        val token = client.pollDeviceToken(authorization.deviceCode, KimiRegion.GLOBAL)
        val mainland = client.pollDeviceToken("dc")

        assertEquals(
            listOf(
                "https://auth.kimi.ai/api/oauth/device_authorization",
                "https://auth.kimi.ai/api/oauth/token",
                "https://auth.kimi.com/api/oauth/token",
            ),
            http.requests.map { it.uri().toString() },
        )
        assertEquals("global", (token as KimiDeviceTokenPollResult.Authorized).credentials.region)
        assertEquals(null, (mainland as KimiDeviceTokenPollResult.Authorized).credentials.region)
        assertEquals(
            "https://www.kimi.ai/code/authorize_device?user_code=UC",
            authorization.verificationUriComplete,
        )
    }

    @Test
    fun fetchWrapsTheMarkdownForTheGlobalHostToo() {
        val http = RecordingHttpClient { 200 to "# Page" }
        val result =
            KimiWebSearchClient(httpClient = http)
                .webFetch(
                    KimiCredentials(accessToken = "token", region = "global"),
                    "https://example.com",
                )

        assertEquals("https://api.kimi.ai/coding/v1/fetch", http.requests.single().uri().toString())
        assertEquals(
            "# Page",
            JsonSupport.json
                .parseToJsonElement(result.body)
                .jsonObject["content"]!!
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun proxyDiscoversModelsFromTheLoginRegion() {
        val http = RecordingHttpClient {
            200 to """{"data":[{"id":"kimi-for-coding","limit":{"context":1000000}}]}"""
        }
        var credentials = KimiCredentials(accessToken = "token", region = "global")
        val proxy =
            de.moritzf.quota.kimi.proxy.KimiSubscriptionProxyProvider(
                credentialsProvider = { credentials },
                httpClient = http,
                modelsDevCatalogUri = null,
                deviceId = "device",
            )

        assertEquals(listOf("ki-kimi-for-coding"), proxy.models().map { it.localId })
        credentials = KimiCredentials(accessToken = "token")
        proxy.models()

        assertEquals(
            listOf(
                "https://api.kimi.ai/coding/v1/models",
                "https://api.kimi.com/coding/v1/models",
            ),
            http.requests.map { it.uri().toString() },
            "a different region does not reuse the cached list",
        )
    }

    private class RecordingHttpClient(private val answer: (HttpRequest) -> Pair<Int, String>) :
        HttpClient() {
        val requests = mutableListOf<HttpRequest>()

        override fun <T : Any?> send(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): HttpResponse<T> {
            requests += request
            val (status, body) = answer(request)
            @Suppress("UNCHECKED_CAST")
            return Response(request, status, body) as HttpResponse<T>
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

    private class Response(
        private val request: HttpRequest,
        private val status: Int,
        private val body: String,
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
}
