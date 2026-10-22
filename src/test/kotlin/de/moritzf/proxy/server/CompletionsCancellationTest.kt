package de.moritzf.proxy.server

import de.moritzf.proxy.fim.CompletionsConfig
import de.moritzf.proxy.logging.RequestLogger
import de.moritzf.proxy.subscription.SubscriptionModelCatalog
import de.moritzf.proxy.subscription.SubscriptionProxyModel
import de.moritzf.proxy.subscription.SubscriptionProxyProvider
import de.moritzf.proxy.subscription.SubscriptionProxyRequest
import de.moritzf.proxy.subscription.SubscriptionProxyRoute
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.io.InputStream
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class CompletionsCancellationTest {
    @Test
    fun cancellationAfterResponseBeforeDispatchClosesTheUnclaimedBody() = runTest {
        val client = PendingHttpClient()
        val handler = handler(client)
        val body = TrackedBody()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { await(handler).body().close() }
        client.response.complete(Response(body))
        job.cancel()
        runCurrent()
        assertTrue(body.closed.await(5, TimeUnit.SECONDS), "Unclaimed response body leaked")
        assertEquals(1, body.closes.get())
    }

    @Test
    fun responseArrivingAfterCancellationIsClosed() = runTest {
        val client = PendingHttpClient(ignoreCancellation = true)
        val body = TrackedBody()
        val job =
            launch(start = CoroutineStart.UNDISPATCHED) { await(handler(client)).body().close() }
        job.cancel()
        runCurrent()
        client.response.complete(Response(body))
        assertTrue(body.closed.await(5, TimeUnit.SECONDS))
        assertEquals(1, body.closes.get())
    }

    @Test
    fun cancellationInterruptsBodyReadsAndClosesClaimedResponses() {
        for (stream in listOf(false, true)) {
            for (contentType in listOf("application/json", "text/event-stream")) {
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val body =
                    object : TrackedBody() {
                        override fun read(): Int {
                            entered.countDown()
                            check(release.await(5, TimeUnit.SECONDS)) { "Read was not interrupted" }
                            return -1
                        }
                    }
                val upstream =
                    PendingHttpClient().apply {
                        response.complete(Response(body, contentType = contentType))
                    }
                withServer(upstream) { port, requestJob ->
                    HttpClient.newHttpClient().use { client ->
                        val request =
                            client.sendAsync(
                                request(port, stream),
                                HttpResponse.BodyHandlers.ofString(),
                            )
                        try {
                            assertTrue(
                                entered.await(5, TimeUnit.SECONDS),
                                "Body read was not reached",
                            )
                            requestJob.get(5, TimeUnit.SECONDS).cancel()
                            assertTrue(
                                body.closed.await(5, TimeUnit.SECONDS),
                                "Claimed body leaked: $contentType stream=$stream",
                            )
                            assertEquals(1, body.closes.get())
                        } finally {
                            release.countDown()
                            request.cancel(true)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun successRateLimitAndErrorResponsesCloseTheirBodiesExactlyOnce() {
        for (stream in listOf(false, true)) {
            for (status in listOf(200, 429, 502)) {
                for (contentType in listOf("application/json", "text/event-stream")) {
                    val payload =
                        if (contentType == "application/json") {
                            """{"choices":[{"message":{"content":"answer"}}]}"""
                        } else {
                            "data: {\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}\n\ndata: [DONE]\n\n"
                        }
                    val input = payload.byteInputStream()
                    val body =
                        object : TrackedBody() {
                            override fun read() = input.read()
                        }
                    val upstream =
                        PendingHttpClient().apply {
                            response.complete(Response(body, status, contentType))
                        }
                    withServer(upstream) { port, _ ->
                        HttpClient.newHttpClient().use { client ->
                            val response =
                                client.send(
                                    request(port, stream),
                                    HttpResponse.BodyHandlers.ofString(),
                                )
                            assertEquals(
                                if (status == 502 && !stream) 502 else 200,
                                response.statusCode(),
                            )
                            if (status == 200)
                                assertTrue(response.body().contains("answer"), response.body())
                            assertTrue(body.closed.await(5, TimeUnit.SECONDS))
                            assertEquals(1, body.closes.get())
                        }
                    }
                }
            }
        }
    }

    @Test
    fun liteLlmBudgetErrorsEndTheCompletionLikeRateLimits() {
        val budget =
            """{"error":{"message":"Budget has been exceeded! Key=my-alias Current cost: 10.01, Max budget: 10.0","type":"budget_exceeded","param":null,"code":"422"}}"""
        val validation = """{"detail":[{"loc":["body","prompt"],"msg":"field required"}]}"""
        for ((payload, expected) in listOf(budget to 200, validation to 422)) {
            val input = payload.byteInputStream()
            val body =
                object : TrackedBody() {
                    override fun read() = input.read()
                }
            val upstream =
                PendingHttpClient().apply {
                    response.complete(Response(body, 422, "application/json"))
                }
            withServer(upstream) { port, _ ->
                HttpClient.newHttpClient().use { client ->
                    val response =
                        client.send(request(port, false), HttpResponse.BodyHandlers.ofString())
                    assertEquals(expected, response.statusCode(), response.body())
                    if (expected == 422) assertEquals(validation, response.body())
                }
            }
        }
    }

    private fun request(port: Int, stream: Boolean): HttpRequest =
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/completions"))
            .timeout(java.time.Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""{"prompt":"return ","stream":$stream}"""))
            .build()

    private fun withServer(upstream: HttpClient, block: (Int, CompletableFuture<Job>) -> Unit) {
        val requestJob = CompletableFuture<Job>()
        val handler = handler(upstream)
        val server =
            embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                    routing {
                        post("/v1/completions") {
                            requestJob.complete(currentCoroutineContext().job)
                            handler.handle(ProxyCall(call))
                        }
                    }
                }
                .start(wait = false)
        try {
            block(runBlocking { server.engine.resolvedConnectors().single().port }, requestJob)
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 1000)
        }
    }

    private fun handler(client: HttpClient) =
        CompletionsHandler(
            { SubscriptionModelCatalog(listOf(PROVIDER)) },
            {
                CompletionsConfig(enabled = true, modelLocalId = "test-chat", timeoutMillis = 10000)
            },
            RequestLogger(false, Path.of("unused")),
            "localhost",
            1,
            { "unused" },
            httpClient = client,
        )

    private suspend fun await(handler: CompletionsHandler): HttpResponse<InputStream> =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            val method =
                CompletionsHandler::class
                    .java
                    .getDeclaredMethod(
                        "sendAsync",
                        HttpRequest::class.java,
                        Continuation::class.java,
                    )
            method.isAccessible = true
            method.invoke(handler, REQUEST, continuation)
        }

    private open class TrackedBody : InputStream() {
        val closes = AtomicInteger()
        val closed = CountDownLatch(1)

        override fun read() = -1

        override fun close() {
            closes.incrementAndGet()
            closed.countDown()
        }
    }

    private class PendingHttpClient(ignoreCancellation: Boolean = false) : HttpClient() {
        val response =
            object : CompletableFuture<HttpResponse<InputStream>>() {
                override fun cancel(mayInterruptIfRunning: Boolean): Boolean =
                    if (ignoreCancellation) false else super.cancel(mayInterruptIfRunning)
            }

        override fun <T> send(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): HttpResponse<T> = error("Async only")

        @Suppress("UNCHECKED_CAST")
        override fun <T> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): CompletableFuture<HttpResponse<T>> = response as CompletableFuture<HttpResponse<T>>

        override fun <T> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
            pushPromiseHandler: HttpResponse.PushPromiseHandler<T>,
        ): CompletableFuture<HttpResponse<T>> = error("No push")

        override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()

        override fun connectTimeout(): Optional<java.time.Duration> = Optional.empty()

        override fun followRedirects() = Redirect.NEVER

        override fun proxy(): Optional<ProxySelector> = Optional.empty()

        override fun sslContext(): SSLContext = SSLContext.getDefault()

        override fun sslParameters() = SSLParameters()

        override fun authenticator(): Optional<Authenticator> = Optional.empty()

        override fun version() = Version.HTTP_1_1

        override fun executor(): Optional<Executor> = Optional.empty()
    }

    private class Response(
        private val body: InputStream,
        private val status: Int = 200,
        private val contentType: String = "application/json",
    ) : HttpResponse<InputStream> {
        override fun statusCode() = status

        override fun request() = REQUEST

        override fun previousResponse(): Optional<HttpResponse<InputStream>> = Optional.empty()

        override fun headers(): HttpHeaders =
            HttpHeaders.of(mapOf("Content-Type" to listOf(contentType))) { _, _ -> true }

        override fun body() = body

        override fun sslSession(): Optional<SSLSession> = Optional.empty()

        override fun uri(): URI = REQUEST.uri()

        override fun version() = HttpClient.Version.HTTP_1_1
    }

    companion object {
        private val REQUEST = HttpRequest.newBuilder(URI("http://localhost/unused")).build()
        private val PROVIDER =
            object : SubscriptionProxyProvider {
                override val id = "test"
                override val displayName = "Test"

                override fun isConfigured() = true

                override fun models() =
                    listOf(
                        SubscriptionProxyModel(
                            "test-chat",
                            "chat",
                            id,
                            displayName,
                            "openai",
                            setOf(SubscriptionProxyRoute.CHAT_COMPLETIONS),
                        )
                    )

                override suspend fun handle(ctx: ProxyCall, request: SubscriptionProxyRequest) =
                    error("Chat upstream is supplied by the HTTP client")
            }
    }
}
