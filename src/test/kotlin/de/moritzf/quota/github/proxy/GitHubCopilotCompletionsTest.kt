package de.moritzf.quota.github.proxy

import com.sun.net.httpserver.HttpServer
import de.moritzf.proxy.fim.CompletionsConfig
import de.moritzf.proxy.fim.FimModels
import de.moritzf.proxy.server.JsonHelper
import de.moritzf.proxy.subscription.SubscriptionProxyRoute
import de.moritzf.proxy.subscription.SubscriptionProxyServer
import de.moritzf.quota.github.GitHubQuotaException
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class GitHubCopilotCompletionsTest {
    @Test
    fun discoversNativeModelsHiddenFromChatPickerWithoutAdvertisingChatOrTools() {
        Upstream().use { upstream ->
            val models = upstream.provider().models()
            assertEquals(
                listOf("gh-gpt-41-copilot", "gh-future-completion"),
                models.map { it.localId },
            )
            models.forEach {
                assertEquals(setOf(SubscriptionProxyRoute.COMPLETIONS), it.supportedRoutes)
                assertFalse(it.supportsFunctionCalling)
                assertFalse(it.supportsToolChoice)
                assertTrue(FimModels.isNativeFimId(it))
                assertTrue(FimModels.isEligible(it))
            }
            assertTrue(parseGitHubListedModels(MODELS).isEmpty())
            assertEquals(0, upstream.exchanges.get())
        }
    }

    @Test
    fun nativeFimUsesSessionEndpointAndPreservesPrefixSuffixAndWhitespaceForBothResponseModes() {
        Upstream().use { upstream ->
            withProxy(upstream) { port ->
                for (stream in listOf(false, true)) {
                    val response = completion(port, stream)
                    assertEquals(200, response.statusCode(), response.body())
                    if (stream) {
                        assertTrue(
                            response
                                .headers()
                                .firstValue("Content-Type")
                                .orElse("")
                                .contains("text/event-stream")
                        )
                        val chunks =
                            response
                                .body()
                                .lineSequence()
                                .filter { it.startsWith("data: {") }
                                .map { parse(it.substringAfter("data: ")) }
                                .toList()
                        assertEquals(
                            "a + b",
                            chunks.joinToString("") {
                                it["choices"]!!
                                    .jsonArray[0]
                                    .jsonObject["text"]!!
                                    .jsonPrimitive
                                    .content
                            },
                        )
                        assertEquals(
                            "stop",
                            chunks
                                .last()["choices"]!!
                                .jsonArray[0]
                                .jsonObject["finish_reason"]!!
                                .jsonPrimitive
                                .content,
                        )
                        assertTrue(response.body().endsWith("data: [DONE]\n\n"))
                    } else {
                        val body = parse(response.body())
                        assertEquals("text_completion", body["object"]!!.jsonPrimitive.content)
                        assertEquals(
                            "a + b",
                            body["choices"]!!
                                .jsonArray[0]
                                .jsonObject["text"]!!
                                .jsonPrimitive
                                .content,
                        )
                    }
                    val sent = parse(upstream.lastCompletionBody)
                    assertEquals(
                        "function add(a, b) { return ",
                        sent["prompt"]!!.jsonPrimitive.content,
                    )
                    assertEquals("; }", sent["suffix"]!!.jsonPrimitive.content)
                    assertEquals("true", sent["stream"]!!.jsonPrimitive.content)
                    assertEquals("1", sent["n"]!!.jsonPrimitive.content)
                    assertTrue(sent["stop"]!!.jsonArray.size <= 4)
                    assertFalse("messages" in sent)
                    assertFalse("model" in sent)
                    assertEquals("Bearer session-1", upstream.lastCompletionAuthorization)
                    assertEquals("copilot-ghost", upstream.lastCompletionIntent)
                    assertEquals("vscode-chat", upstream.lastCompletionIntegration)
                }
                assertEquals(1, upstream.exchanges.get())
                assertEquals("token github-token", upstream.lastExchangeAuthorization)
                assertEquals("vscode-chat", upstream.lastExchangeIntegration)
            }
        }
    }

    @Test
    fun retriesRejectedSessionOnceAndCachesReplacement() {
        Upstream().use { upstream ->
            upstream.rejectFirstCompletion = true
            withProxy(upstream) { port ->
                assertEquals(200, completion(port).statusCode())
                assertEquals(2, upstream.exchanges.get())
                assertEquals(2, upstream.completions.get())
                assertEquals("Bearer session-2", upstream.lastCompletionAuthorization)
                assertEquals(200, completion(port).statusCode())
                assertEquals(2, upstream.exchanges.get())
            }
        }
    }

    @Test
    fun rejectsLegacyLoginWithActionableErrorWithoutSendingRawOAuthToCompletions() {
        Upstream().use { upstream ->
            upstream.exchangeStatus = 403
            withProxy(upstream) { port ->
                val response = completion(port)
                assertEquals(401, response.statusCode())
                assertTrue(response.body().contains("Log out and log in"), response.body())
                assertFalse(response.body().contains("secret-from-upstream"))
                assertEquals(0, upstream.completions.get())
            }
        }
    }

    @Test
    fun forwardsCompletionRateLimitWithoutRefreshingSession() {
        Upstream().use { upstream ->
            upstream.completionStatus = 429
            withProxy(upstream) { port ->
                assertEquals(429, completion(port).statusCode())
                assertEquals(1, upstream.exchanges.get())
                assertEquals(1, upstream.completions.get())
            }
        }
    }

    @Test
    fun truncatedOrNonStreamingUpstreamDoesNotBecomeSuccessfulEmptyCompletion() {
        Upstream().use { upstream ->
            withProxy(upstream) { port ->
                upstream.completionBody = "data: {\"choices\":[{\"text\":\"partial\"}]}\n\n"
                assertEquals(502, completion(port).statusCode())
                upstream.completionContentType = "application/json"
                upstream.completionBody = """{"error":{"message":"failed"}}"""
                assertEquals(502, completion(port).statusCode())
            }
        }
    }

    @Test
    fun enforcesStopsBeyondUpstreamLimitAcrossChunkBoundaries() {
        Upstream().use { upstream ->
            upstream.completionBody =
                "data: {\"choices\":[{\"text\":\"answer <FI\"}]}\n\ndata: {\"choices\":[{\"text\":\"FTH>leak\",\"finish_reason\":\"length\"}]}\n\ndata: [DONE]\n\n"
            withProxy(upstream) { port ->
                for (stream in listOf(false, true)) {
                    val response =
                        completion(
                            port,
                            stream,
                            listOf("<ONE>", "<TWO>", "<THREE>", "<FOUR>", "<FIFTH>"),
                        )
                    assertEquals(200, response.statusCode(), response.body())
                    assertFalse(response.body().contains("leak"), response.body())
                    assertFalse(response.body().contains("<FI"), response.body())
                    assertTrue(response.body().contains("answer "), response.body())
                    assertTrue(
                        response.body().contains("\"finish_reason\":\"stop\""),
                        response.body(),
                    )
                    assertEquals(
                        listOf("<ONE>", "<TWO>", "<THREE>", "<FOUR>"),
                        parse(upstream.lastCompletionBody)["stop"]!!.jsonArray.map {
                            it.jsonPrimitive.content
                        },
                    )
                }
            }
        }
    }

    @Test
    fun sessionRefreshHonorsServerRefreshIntervalExpiryAndChangedLogin() {
        Upstream().use { upstream ->
            val clock = MutableClock()
            upstream.expiresAt = clock.instant().epochSecond + 300
            upstream.refreshIn = 30
            var githubToken: String? = "first"
            val sessions =
                GitHubCopilotSessionClient(
                    { githubToken },
                    HttpClient.newHttpClient(),
                    upstream.uri("/token"),
                    clock,
                )
            assertEquals("session-1", sessions.session().token)
            assertEquals("session-1", sessions.session().token)
            clock.now = clock.now.plusSeconds(31)
            assertEquals("session-2", sessions.session().token)
            githubToken = "second"
            assertEquals("session-3", sessions.session().token)
            assertEquals("token second", upstream.lastExchangeAuthorization)
            githubToken = null
            assertEquals(
                401,
                assertFailsWith<GitHubQuotaException> { sessions.session() }.statusCode,
            )
            githubToken = "second"
            assertEquals("session-4", sessions.session().token)

            upstream.refreshIn = 3600
            clock.now = clock.now.plusSeconds(31)
            assertEquals("session-5", sessions.session().token)
            clock.now = Instant.ofEpochSecond(upstream.expiresAt - 59)
            assertEquals("session-6", sessions.session().token)
            assertEquals(6, upstream.exchanges.get())
        }
    }

    @Test
    fun concurrentRefreshesForSameRejectedTokenUseOneExchange() {
        Upstream().use { upstream ->
            val sessions =
                GitHubCopilotSessionClient(
                    { "github-token" },
                    HttpClient.newHttpClient(),
                    upstream.uri("/token"),
                )
            val stale = sessions.session().token
            Executors.newFixedThreadPool(6).use { executor ->
                val ready = CountDownLatch(6)
                val start = CountDownLatch(1)
                val futures =
                    (1..6).map {
                        executor.submit<String> {
                            ready.countDown()
                            check(start.await(5, TimeUnit.SECONDS))
                            sessions.session(stale).token
                        }
                    }
                try {
                    assertTrue(ready.await(5, TimeUnit.SECONDS))
                } finally {
                    start.countDown()
                }
                assertEquals(
                    setOf("session-2"),
                    futures.map { it.get(5, TimeUnit.SECONDS) }.toSet(),
                )
                assertEquals(2, upstream.exchanges.get())
            }
        }
    }

    @Test
    fun invalidTokenResponseNeverLeaksItsBody() {
        Upstream().use { upstream ->
            upstream.tokenBody = """{"token":"secret-from-upstream","expires_at":"broken"}"""
            val sessions =
                GitHubCopilotSessionClient(
                    { "github-token" },
                    HttpClient.newHttpClient(),
                    upstream.uri("/token"),
                )
            val error = assertFailsWith<GitHubQuotaException> { sessions.session() }
            assertEquals(502, error.statusCode)
            assertEquals(null, error.rawBody)
            assertEquals(null, error.cause)
            assertFalse(error.message.orEmpty().contains("secret-from-upstream"))
        }
    }

    private fun withProxy(upstream: Upstream, action: (Int) -> Unit) {
        val provider = upstream.provider()
        val server =
            SubscriptionProxyServer(
                port = 0,
                localApiKeyProvider = { "local-key" },
                providers = { listOf(provider) },
                completionsConfig = {
                    CompletionsConfig(
                        enabled = true,
                        modelLocalId = "gh-gpt-41-copilot",
                        minIntervalMillis = 0,
                    )
                },
            )
        server.start()
        try {
            action(runBlocking { server.boundPort() })
        } finally {
            server.stop(gracePeriodMillis = 0)
        }
    }

    private fun completion(
        port: Int,
        stream: Boolean = false,
        stops: List<String> = emptyList(),
    ): HttpResponse<String> =
        HttpClient.newHttpClient().use { client ->
            client.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/completions"))
                    .header("Authorization", "Bearer local-key")
                    .header("Content-Type", "application/json")
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            """{"model":"qwen2.5-coder","prompt":"function add(a, b) { return ","suffix":"; }","max_tokens":32,"stream":$stream,"stop":${JsonArray(stops.map(::JsonPrimitive))}}"""
                        )
                    )
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }

    private class MutableClock : Clock() {
        var now: Instant = Instant.parse("2026-10-13T12:00:00Z")

        override fun instant() = now

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this
    }

    private class Upstream : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val exchanges = AtomicInteger()
        val completions = AtomicInteger()
        var expiresAt = Instant.now().epochSecond + 3600
        var refreshIn = 1800
        var exchangeStatus = 200
        var completionStatus = 200
        var rejectFirstCompletion = false
        var tokenBody: String? = null
        var completionBody: String? = null
        var completionContentType = "text/event-stream"
        var lastExchangeAuthorization: String? = null
        var lastExchangeIntegration: String? = null
        var lastCompletionAuthorization: String? = null
        var lastCompletionIntegration: String? = null
        var lastCompletionIntent: String? = null
        var lastCompletionBody = ""

        init {
            server.createContext("/") { exchange ->
                var status = 200
                var contentType = "application/json"
                val body =
                    when (exchange.requestURI.path) {
                        "/models" -> MODELS
                        "/token" -> {
                            val count = exchanges.incrementAndGet()
                            lastExchangeAuthorization =
                                exchange.requestHeaders.getFirst("Authorization")
                            lastExchangeIntegration =
                                exchange.requestHeaders.getFirst("Copilot-Integration-Id")
                            status = exchangeStatus
                            tokenBody
                                ?: if (status == 200)
                                    """{"token":"session-$count","expires_at":$expiresAt,"refresh_in":$refreshIn,"endpoints":{"proxy":"${uri("/completion-proxy")}"}}"""
                                else "secret-from-upstream"
                        }
                        "/completion-proxy/v1/engines/gpt-41-copilot/completions" -> {
                            val count = completions.incrementAndGet()
                            lastCompletionAuthorization =
                                exchange.requestHeaders.getFirst("Authorization")
                            lastCompletionIntegration =
                                exchange.requestHeaders.getFirst("Copilot-Integration-Id")
                            lastCompletionIntent = exchange.requestHeaders.getFirst("OpenAI-Intent")
                            lastCompletionBody =
                                exchange.requestBody.bufferedReader().use { it.readText() }
                            status =
                                when {
                                    (parse(lastCompletionBody)["stop"] as? JsonArray)?.size?.let {
                                        it > 4
                                    } == true -> 400
                                    rejectFirstCompletion && count == 1 -> 401
                                    else -> completionStatus
                                }
                            if (status != 200) """{"error":{"message":"upstream failure"}}"""
                            else {
                                contentType = completionContentType
                                completionBody
                                    ?: ": keepalive\n\ndata: {\"choices\":[{\"text\":\"a\",\"index\":0}]}\n\ndata: {\"choices\":[{\"text\":\" \",\"index\":0}]}\n\ndata: {\"choices\":[{\"text\":\"+ b\",\"index\":0,\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                            }
                        }
                        else -> {
                            status = 404
                            "unknown path"
                        }
                    }
                val bytes = body.toByteArray()
                exchange.responseHeaders.set("Content-Type", contentType)
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        fun uri(path: String) = URI("http://127.0.0.1:${server.address.port}$path")

        fun provider() =
            GitHubCopilotSubscriptionProxyProvider(
                accessTokenProvider = { "github-token" },
                upstreamBaseUri = uri(""),
                sessionTokenEndpoint = uri("/token"),
            )

        override fun close() = server.stop(0)
    }

    companion object {
        private fun parse(body: String): JsonObject =
            JsonHelper.JSON.parseToJsonElement(body).jsonObject

        private val MODELS =
            """{"data":[
            {"id":"gpt-41-copilot","model_picker_enabled":false,"capabilities":{"type":"completion","supports":{"streaming":true}}},
            {"id":"future-completion","model_picker_enabled":true,"capabilities":{"type":"completion"}},
            {"id":"disabled-completion","capabilities":{"type":"completion"},"policy":{"state":"disabled"}},
            {"id":"hidden-chat","model_picker_enabled":false,"capabilities":{"type":"chat"}}
        ]}"""
    }
}
