package de.moritzf.quota.idea.auth

import kotlinx.coroutines.runBlocking
import org.intellij.lang.annotations.Language
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
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OAuthTokenClientTest {
    @Test
    fun exchangeUsesJwtExpiryWhenExpiresInIsMissing() = runBlocking {
        val expiresAtSeconds = System.currentTimeMillis() / 1000 + 3600
        val accessToken = buildToken("""{"exp":$expiresAtSeconds}""")
        val client = OAuthTokenClient(
            FakeHttpClient(responseBody = tokenResponse(accessToken = accessToken, refreshToken = "refresh-token")),
            config(),
        )

        val credentials = client.exchangeAuthorizationCode("code", "verifier")

        assertEquals(accessToken, credentials.accessToken)
        assertEquals("refresh-token", credentials.refreshToken)
        assertEquals(expiresAtSeconds * 1000L, credentials.expiresAt)
    }

    @Test
    fun exchangeFallsBackToOneHourForOpaqueTokenWithoutExpiresIn() = runBlocking {
        val before = System.currentTimeMillis()
        val client = OAuthTokenClient(
            FakeHttpClient(responseBody = tokenResponse(accessToken = "opaque-token", refreshToken = "refresh-token")),
            config(),
        )

        val credentials = client.exchangeAuthorizationCode("code", "verifier")

        val after = System.currentTimeMillis()
        assertTrue(credentials.expiresAt in (before + ONE_HOUR_MS)..(after + ONE_HOUR_MS))
    }

    @Test
    fun exchangePrefersPositiveExpiresInOverJwtExpiry() = runBlocking {
        val jwtExpirySeconds = System.currentTimeMillis() / 1000 + 24 * 3600
        val accessToken = buildToken("""{"exp":$jwtExpirySeconds}""")
        val before = System.currentTimeMillis()
        val client = OAuthTokenClient(
            FakeHttpClient(
                responseBody = tokenResponse(
                    accessToken = accessToken,
                    refreshToken = "refresh-token",
                    expiresIn = 120,
                ),
            ),
            config(),
        )

        val credentials = client.exchangeAuthorizationCode("code", "verifier")

        val after = System.currentTimeMillis()
        assertTrue(credentials.expiresAt in (before + 120_000)..(after + 120_000))
    }

    private fun config(): OAuthClientConfig {
        return OAuthClientConfig(
            clientId = "client-id",
            authorizationEndpoint = "https://auth.test/authorize",
            tokenEndpoint = "https://auth.test/token",
            redirectUri = "http://127.0.0.1:56121/callback",
            originator = "test",
            scopes = "openid offline_access",
            callbackPort = 56121,
        )
    }

    private fun tokenResponse(accessToken: String, refreshToken: String, expiresIn: Long? = null): String {
        return buildString {
            append("{\"access_token\":\"")
            append(accessToken)
            append("\",\"refresh_token\":\"")
            append(refreshToken)
            append("\"")
            if (expiresIn != null) {
                append(",\"expires_in\":")
                append(expiresIn)
            }
            append("}")
        }
    }

    private fun buildToken(@Language("JSON") payloadJson: String): String {
        @Language("JSON")
        val headerJson = """
            {"alg":"none","typ":"JWT"}
        """.trimIndent()
        return "${base64Url(headerJson)}.${base64Url(payloadJson)}.signature"
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun base64Url(value: String): String {
        return Base64.UrlSafe
            .withPadding(Base64.PaddingOption.ABSENT)
            .encode(value.toByteArray(Charsets.UTF_8))
    }

    private class FakeHttpClient(private val responseBody: String) : HttpClient() {
        override fun <T : Any?> send(request: HttpRequest, responseBodyHandler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
            @Suppress("UNCHECKED_CAST")
            return FakeResponse(responseBody, request) as HttpResponse<T>
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
        private val responseBody: String,
        private val request: HttpRequest,
    ) : HttpResponse<String> {
        override fun statusCode(): Int = 200
        override fun request(): HttpRequest = request
        override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()
        override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }
        override fun body(): String = responseBody
        override fun sslSession(): Optional<SSLSession> = Optional.empty()
        override fun uri(): URI = request.uri()
        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
    }

    private companion object {
        private const val ONE_HOUR_MS = 60 * 60 * 1000L
    }
}
