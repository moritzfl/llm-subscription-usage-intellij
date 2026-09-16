package de.moritzf.quota.kimi

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KimiCredentialRefresherTest {
    @Test
    fun refreshIsSingleFlightForTheSameRefreshToken() {
        val tokenHits = AtomicInteger(0)
        TestTokenServer(tokenHits).use { server ->
            val refresher = KimiCredentialRefresher(HttpClient.newHttpClient(), server.uri)
            val credentials = KimiCredentials(
                accessToken = "stale",
                refreshToken = "refresh-1",
                expiresAtEpochSeconds = 1.0,
            )
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(4)
            val futures = (1..4).map {
                executor.submit<KimiCredentials?> {
                    start.await()
                    refresher.refresh(credentials)
                }
            }
            start.countDown()
            val results = futures.map { it.get(5, TimeUnit.SECONDS) }
            executor.shutdownNow()

            assertEquals(1, tokenHits.get())
            assertTrue(results.all { it?.accessToken == "fresh-token" })
        }
    }

    private class TestTokenServer(private val tokenHits: AtomicInteger) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val uri: URI

        init {
            server.createContext("/") { exchange ->
                tokenHits.incrementAndGet()
                Thread.sleep(150)
                val body = """{"access_token":"fresh-token","refresh_token":"refresh-2","expires_in":3600}"""
                    .toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            uri = URI.create("http://127.0.0.1:${server.address.port}/")
        }

        override fun close() {
            server.stop(0)
        }
    }
}
