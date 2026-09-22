package de.moritzf.quota.azure.proxy

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import de.moritzf.proxy.subscription.SubscriptionProxyServer
import de.moritzf.quota.azure.AzureAccountConfig
import de.moritzf.quota.azure.AzureCli
import de.moritzf.quota.azure.AzureLiveUsage
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AzureProxyRequestTest {
    @Test
    fun overlappingRequestsKeepTheirOwnAccountAndDeploymentCounters() {
        val slowStarted = CountDownLatch(1)
        val releaseSlow = CountDownLatch(1)
        val upstream = TestUpstream { exchange, body ->
            if ("slow" in body) {
                slowStarted.countDown()
                check(releaseSlow.await(10, TimeUnit.SECONDS))
                reply(exchange, remaining = 10)
            } else {
                reply(exchange, remaining = 80)
            }
        }
        try {
            val first = config(upstream.url, "first")
            val second = config(upstream.url, "second")
            val selected = AtomicReference(first)
            withProxy(selected::get) { port ->
                val slow = postAsync(port, "slow")
                assertTrue(slowStarted.await(5, TimeUnit.SECONDS))
                selected.set(second)
                assertEquals(200, postAsync(port, "fast").get(5, TimeUnit.SECONDS).statusCode())
                releaseSlow.countDown()
                assertEquals(200, slow.get(5, TimeUnit.SECONDS).statusCode())
                val firstUsage = assertNotNull(AzureLiveUsage.read(AzureLiveUsage.key(first.accountId, first.account)))
                val secondUsage = assertNotNull(AzureLiveUsage.read(AzureLiveUsage.key(second.accountId, second.account)))
                assertEquals("slow", firstUsage.model)
                assertEquals(10.0, firstUsage.remainingTokens)
                assertEquals("fast", secondUsage.model)
                assertEquals(80.0, secondUsage.remainingTokens)
            }
        } finally {
            releaseSlow.countDown()
            upstream.close()
        }
    }

    @Test
    fun settingsChangeDuringTokenAcquisitionDoesNotRetargetRequestOrRetry() {
        val requests = LinkedBlockingQueue<Pair<String, String>>()
        TestUpstream { exchange, _ ->
            val token = exchange.requestHeaders.getFirst("Authorization")
            requests += exchange.requestURI.toString() to token
            reply(exchange, status = if (token == "Bearer first-token") 401 else 200)
        }.use { upstream ->
            val first = config("${upstream.url}/first", "first")
            val second = config("${upstream.url}/second", "second")
            val selected = AtomicReference(first)
            val calls = LinkedBlockingQueue<List<String>>()
            val count = AtomicInteger()
            withProxy(selected::get, cliFactory = { path ->
                AzureCli(path, run = { _, args, _, _ ->
                    calls += args
                    selected.set(second)
                    val token = if (count.incrementAndGet() == 1) "first-token" else "refreshed-token"
                    """{"accessToken":"$token","expires_on":4102444800}"""
                })
            }) { port ->
                assertEquals(200, postAsync(port, "chat").get(5, TimeUnit.SECONDS).statusCode())
                assertEquals(2, requests.size)
                assertTrue(requests.all { it.first == "/openai/v1/first/chat/completions?api-version=v1" }, requests.toString())
                assertEquals(listOf("Bearer first-token", "Bearer refreshed-token"), requests.map { it.second })
                assertTrue(calls.all { it[it.indexOf("--subscription") + 1] == first.account.subscriptionId })
                assertNotNull(AzureLiveUsage.read(AzureLiveUsage.key(first.accountId, first.account)))
            }
        }
    }

    private fun config(url: String, id: String) = AzureSubscriptionProxyProvider.AzureProxyConfig(
        accountId = "$id-${UUID.randomUUID()}",
        executable = Path.of("/test/az"),
        account = AzureAccountConfig(subscriptionId = "$id-subscription", endpoint = url),
    )

    private fun withProxy(
        configProvider: () -> AzureSubscriptionProxyProvider.AzureProxyConfig,
        cliFactory: (Path) -> AzureCli = { path ->
            AzureCli(path, run = { _, _, _, _ -> """{"accessToken":"test-token","expires_on":4102444800}""" })
        },
        action: (Int) -> Unit,
    ) {
        val logDir = Files.createTempDirectory("azure-proxy-test-logs").toString()
        val provider = AzureSubscriptionProxyProvider(configProvider, cliFactory, requestLogDir = logDir)
        val port = ServerSocket(0).use { it.localPort }
        val server = SubscriptionProxyServer(port, localApiKeyProvider = { "local-key" }, providers = { listOf(provider) }, requestLogDir = logDir)
        try {
            server.start()
            action(port)
        } finally {
            server.stop()
        }
    }

    private fun postAsync(port: Int, deployment: String) = client.sendAsync(
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/chat/completions"))
            .header("Authorization", "Bearer local-key")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""{"model":"az-$deployment","messages":[]}"""))
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private class TestUpstream(handler: (HttpExchange, String) -> Unit) : AutoCloseable {
        private val executor = Executors.newFixedThreadPool(2)
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            this.executor = this@TestUpstream.executor
            createContext("/") { exchange ->
                exchange.use { handler(it, it.requestBody.readAllBytes().toString(Charsets.UTF_8)) }
            }
            start()
        }
        val url = "http://127.0.0.1:${server.address.port}/openai/v1"

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    companion object {
        private val client = HttpClient.newHttpClient()

        private fun reply(exchange: HttpExchange, status: Int = 200, remaining: Int = 50) {
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.responseHeaders.set("x-ratelimit-limit-tokens", "100")
            exchange.responseHeaders.set("x-ratelimit-remaining-tokens", remaining.toString())
            exchange.responseHeaders.set("x-ratelimit-reset-tokens", "60")
            val body = """{"id":"response","choices":[]}""".toByteArray()
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.write(body)
        }
    }
}
