package de.moritzf.quota.idea.auth

import de.moritzf.quota.shared.auth.OAuthCredentials
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.intellij.lang.annotations.Language

class OAuthTokenClientTest {
    @Test
    fun exchangeUsesJwtExpiryWhenExpiresInIsMissing() = runBlocking {
        val expiresAtSeconds = System.currentTimeMillis() / 1000 + 3600
        val accessToken = buildToken("""{"exp":$expiresAtSeconds}""")
        val client =
            OAuthTokenClient(
                FakeHttpClient(
                    responseBody =
                        tokenResponse(accessToken = accessToken, refreshToken = "refresh-token")
                ),
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
        val client =
            OAuthTokenClient(
                FakeHttpClient(
                    responseBody =
                        tokenResponse(accessToken = "opaque-token", refreshToken = "refresh-token")
                ),
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
        val client =
            OAuthTokenClient(
                FakeHttpClient(
                    responseBody =
                        tokenResponse(
                            accessToken = accessToken,
                            refreshToken = "refresh-token",
                            expiresIn = 120,
                        )
                ),
                config(),
            )

        val credentials = client.exchangeAuthorizationCode("code", "verifier")

        val after = System.currentTimeMillis()
        assertTrue(credentials.expiresAt in (before + 120_000)..(after + 120_000))
    }

    @Test
    fun refreshDoesNotRetryServerErrors() = runBlocking {
        val http =
            FakeHttpClient(
                responseBody =
                    tokenResponse(
                        accessToken = "new-access",
                        refreshToken = "new-refresh",
                        expiresIn = 3600,
                    ),
                failuresBeforeSuccess = 1,
            )

        val failure =
            assertFailsWith<OAuthTokenRequestException> {
                OAuthTokenClient(http, config()).refreshCredentials(expiredCredentials())
            }

        assertEquals(1, http.attempts, "an ambiguous 5xx response must not replay a rotating token")
        assertEquals(503, failure.statusCode)
        assertFalse(
            failure.message.orEmpty().contains("upstream error"),
            "raw token responses must not enter logs",
        )
    }

    @Test
    fun refreshRetriesUnreachableTokenEndpoint() = runBlocking {
        val http =
            FakeHttpClient(
                responseBody =
                    tokenResponse(
                        accessToken = "new-access",
                        refreshToken = "new-refresh",
                        expiresIn = 3600,
                    ),
                failuresBeforeSuccess = 1,
                failWith = { java.net.ConnectException("connection refused") },
            )

        val credentials = OAuthTokenClient(http, config()).refreshCredentials(expiredCredentials())

        assertEquals(2, http.attempts)
        assertEquals("new-access", credentials.accessToken)
    }

    @Test
    fun refreshRetriesPreSendFailureReportedAsCause() = runBlocking {
        val http =
            FakeHttpClient(
                responseBody =
                    tokenResponse(
                        accessToken = "new-access",
                        refreshToken = "new-refresh",
                        expiresIn = 3600,
                    ),
                failuresBeforeSuccess = 1,
                failWith = {
                    java.io.IOException("failed", java.net.UnknownHostException("auth.test"))
                },
            )

        val credentials = OAuthTokenClient(http, config()).refreshCredentials(expiredCredentials())

        assertEquals(2, http.attempts)
        assertEquals("new-access", credentials.accessToken)
    }

    @Test
    fun refreshRetriesConnectTimeout() = runBlocking {
        val http =
            FakeHttpClient(
                responseBody =
                    tokenResponse(
                        accessToken = "new-access",
                        refreshToken = "new-refresh",
                        expiresIn = 3600,
                    ),
                failuresBeforeSuccess = 1,
                failWith = { java.net.http.HttpConnectTimeoutException("connect timed out") },
            )

        val credentials = OAuthTokenClient(http, config()).refreshCredentials(expiredCredentials())

        assertEquals(2, http.attempts)
        assertEquals("new-access", credentials.accessToken)
    }

    @Test
    fun refreshDoesNotRetryRequestTimeout() = runBlocking {
        val http =
            FakeHttpClient(
                responseBody =
                    tokenResponse(
                        accessToken = "new-access",
                        refreshToken = "new-refresh",
                        expiresIn = 3600,
                    ),
                failuresBeforeSuccess = 1,
                failWith = { java.net.http.HttpTimeoutException("request timed out") },
            )

        assertFailsWith<java.net.http.HttpTimeoutException> {
            OAuthTokenClient(http, config()).refreshCredentials(expiredCredentials())
        }

        assertEquals(1, http.attempts, "a request timeout may follow token rotation")
    }

    @Test
    fun refreshDoesNotRetryConnectionDroppedAfterSending() = runBlocking {
        // The endpoint may already have rotated the refresh token, so resending it would be
        // answered with invalid_grant and look like a revoked login.
        val http =
            FakeHttpClient(
                responseBody =
                    tokenResponse(
                        accessToken = "new-access",
                        refreshToken = "new-refresh",
                        expiresIn = 3600,
                    ),
                failuresBeforeSuccess = 1,
                failWith = { java.io.IOException("connection reset") },
            )

        assertFailsWith<java.io.IOException> {
            OAuthTokenClient(http, config()).refreshCredentials(expiredCredentials())
        }

        assertEquals(1, http.attempts, "a spent refresh token must not be resent")
    }

    @Test
    fun refreshDoesNotRetryRejectedTokens() = runBlocking {
        val http =
            FakeHttpClient(
                responseBody = """{"error":"invalid_grant"}""",
                failuresBeforeSuccess = 1,
                failureStatus = 400,
            )

        val failure =
            assertFailsWith<OAuthTokenRequestException> {
                OAuthTokenClient(http, config()).refreshCredentials(expiredCredentials())
            }

        assertEquals(1, http.attempts, "a rejected refresh token is final")
        assertEquals(400, failure.statusCode)
    }

    private fun expiredCredentials(): OAuthCredentials =
        OAuthCredentials(accessToken = "old-access", refreshToken = "old-refresh", expiresAt = 1L)

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

    private fun tokenResponse(
        accessToken: String,
        refreshToken: String,
        expiresIn: Long? = null,
    ): String {
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
        val headerJson =
            """
            {"alg":"none","typ":"JWT"}
            """
                .trimIndent()
        return "${base64Url(headerJson)}.${base64Url(payloadJson)}.signature"
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun base64Url(value: String): String {
        return Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
            .encode(value.toByteArray(Charsets.UTF_8))
    }

    private class FakeHttpClient(
        private val responseBody: String,
        private val failuresBeforeSuccess: Int = 0,
        private val failureStatus: Int = 503,
        private val failWith: (() -> Exception)? = null,
    ) : HttpClient() {
        var attempts: Int = 0

        override fun <T : Any?> send(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): HttpResponse<T> {
            attempts++
            if (attempts <= failuresBeforeSuccess) {
                failWith?.let { throw it() }
                @Suppress("UNCHECKED_CAST")
                return FakeResponse("upstream error", request, failureStatus) as HttpResponse<T>
            }
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
        private val status: Int = 200,
    ) : HttpResponse<String> {
        override fun statusCode(): Int = status

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
