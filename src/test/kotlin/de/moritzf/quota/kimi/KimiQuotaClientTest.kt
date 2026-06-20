package de.moritzf.quota.kimi

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.util.ArrayDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KimiQuotaClientTest {
    @Test
    fun parseQuotaExtractsSessionAndTotalUsage() {
        val body = """
            {
              "usage": {"limit":"100", "remaining":"74", "resetTime":"2026-02-11T17:32:50.757941Z"},
              "limits": [{
                "window": {"duration":300, "timeUnit":"TIME_UNIT_MINUTE"},
                "detail": {"limit":"100", "remaining":"85", "resetTime":"2026-02-07T12:32:50.757941Z"}
              }],
              "user": {"membership": {"level":"LEVEL_INTERMEDIATE"}}
            }
        """.trimIndent()

        val quota = KimiQuotaClient.parseQuota(body)

        assertEquals("Kimi Code Intermediate", quota.plan)
        val session = assertNotNull(quota.sessionUsage)
        assertEquals(15, session.used)
        assertEquals(100, session.limit)
        assertEquals(15.0, session.usagePercent)
        assertEquals(18_000_000, session.periodDurationMs)
        val total = assertNotNull(quota.totalUsage)
        assertEquals(26, total.used)
    }

    @Test
    fun parseQuotaReportsInvalidPayload() {
        val body = "not json"

        val exception = assertFailsWith<KimiQuotaException> {
            KimiQuotaClient.parseQuota(body)
        }

        assertEquals("Could not parse usage data.", exception.message)
        assertEquals(body, exception.rawBody)
    }

    @Test
    fun fetchQuotaRefreshesCredentialsAndRetriesAfterUnauthorizedResponse() {
        TestKimiServer(
            usageResponses = listOf(
                401 to "{\"error\":\"expired\"}",
                200 to """
                    {
                      "usage": {"limit":"100", "remaining":"90"},
                      "limits": [],
                      "user": {"membership": {"level":"LEVEL_PREMIUM"}}
                    }
                """.trimIndent(),
            ),
            tokenBody = """
                {
                  "access_token": "fresh-token",
                  "refresh_token": "refresh-2",
                  "expires_in": 3600
                }
            """.trimIndent(),
        ).use { server ->
            val client = KimiQuotaClient(
                httpClient = httpClient,
                usageEndpoint = server.baseUri.resolve("/usage"),
                tokenEndpoint = server.baseUri.resolve("/token"),
            )

            val result = client.fetchQuota(
                KimiCredentials(
                    accessToken = "stale-token",
                    refreshToken = "refresh-1",
                    expiresAtEpochSeconds = 9_999_999_999.0,
                ),
            )

            assertEquals("Kimi Code Premium", result.quota.plan)
            assertEquals("fresh-token", result.credentials.accessToken)
            assertEquals("refresh-2", result.credentials.refreshToken)
            val firstUsage = assertNotNull(server.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("/usage", firstUsage.path)
            assertEquals("Bearer stale-token", firstUsage.firstHeader("Authorization"))
            val tokenRequest = assertNotNull(server.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("/token", tokenRequest.path)
            assertTrue(tokenRequest.body.contains("refresh_token=refresh-1"))
            val retryUsage = assertNotNull(server.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("/usage", retryUsage.path)
            assertEquals("Bearer fresh-token", retryUsage.firstHeader("Authorization"))
        }
    }

    private class TestKimiServer(
        usageResponses: List<Pair<Int, String>>,
        private val tokenBody: String,
        private val tokenStatus: Int = 200,
    ) : AutoCloseable {
        val requests = LinkedBlockingQueue<CapturedRequest>()
        private val queuedUsageResponses = ArrayDeque(usageResponses)
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val baseUri: URI

        init {
            server.createContext("/") { exchange ->
                val requestBody = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                requests += CapturedRequest(
                    path = exchange.requestURI.rawPath,
                    headers = exchange.requestHeaders.mapValues { it.value.toList() },
                    body = requestBody,
                )

                val (status, body) = when (exchange.requestURI.rawPath) {
                    "/usage" -> nextUsageResponse()
                    "/token" -> tokenStatus to tokenBody
                    else -> 404 to "{}"
                }
                val response = body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, response.size.toLong())
                exchange.responseBody.use { output -> output.write(response) }
            }
            server.start()
            baseUri = URI.create("http://127.0.0.1:${server.address.port}")
        }

        private fun nextUsageResponse(): Pair<Int, String> {
            if (queuedUsageResponses.size > 1) {
                return queuedUsageResponses.removeFirst()
            }
            return queuedUsageResponses.firstOrNull() ?: (500 to "{}")
        }

        override fun close() {
            server.stop(0)
        }
    }

    private data class CapturedRequest(
        val path: String,
        val headers: Map<String, List<String>>,
        val body: String,
    ) {
        fun firstHeader(name: String): String? {
            return headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
        }
    }

    private companion object {
        val httpClient: HttpClient = HttpClient.newHttpClient()
    }
}
