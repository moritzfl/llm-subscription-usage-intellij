package de.moritzf.quota.idea.openai

import com.sun.net.httpserver.HttpServer
import de.moritzf.proxy.fim.CompletionsConfig
import de.moritzf.proxy.server.ProxyCall
import de.moritzf.proxy.subscription.SubscriptionModelCatalog
import de.moritzf.proxy.subscription.SubscriptionProxyModel
import de.moritzf.proxy.subscription.SubscriptionProxyProvider
import de.moritzf.proxy.subscription.SubscriptionProxyRequest
import de.moritzf.proxy.subscription.SubscriptionProxyRoute
import de.moritzf.quota.mistral.proxy.MistralSubscriptionProxyProvider
import de.moritzf.quota.openai.proxy.OpenAiCodexSubscriptionProxyProvider
import de.moritzf.quota.shared.JsonSupport
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CompletionsFimTesterTest {
    @Test
    fun callsNativeFimAndResponsesBackendsDirectly() = runBlocking {
        val requests = CopyOnWriteArrayList<Pair<String, String>>()
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val request = exchange.requestBody.bufferedReader().readText()
            if (exchange.requestMethod == "POST") requests += path to request
            val response =
                when (path) {
                    "/v1/models" -> """{"data":[{"id":"codestral-latest"}]}"""
                    "/v1/fim/completions" -> """{"choices":[{"message":{"content":"a + b"}}]}"""
                    else ->
                        "event: response.completed\ndata: " +
                            """{"type":"response.completed","response":{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"a + b"}]}]}}""" +
                            "\n\n"
                }
            exchange.responseHeaders.set(
                "Content-Type",
                if (path.endsWith("/responses")) "text/event-stream" else "application/json",
            )
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        upstream.start()
        try {
            val base = "http://127.0.0.1:${upstream.address.port}"
            val native =
                MistralSubscriptionProxyProvider({ "draft-key" }, upstreamBaseUri = URI("$base/v1"))
            val codex =
                OpenAiCodexSubscriptionProxyProvider(
                    { "draft-token" },
                    { "draft-account" },
                    upstreamBaseUri = URI(base),
                )
            for ((provider, model) in
                listOf(native to "mi-codestral-latest", codex to "oa-gpt-6-sol")) {
                val result =
                    CompletionsFimTester.test(
                        SubscriptionModelCatalog(listOf(provider)),
                        CompletionsConfig(
                            modelLocalId = model,
                            maxOutputTokens = 123,
                            priorityTier = true,
                        ),
                    )
                assertTrue(result.ok, result.detail ?: result.status)
                assertEquals("a + b", result.insert)
            }
            assertEquals(2, requests.size)
            assertEquals("/v1/fim/completions", requests[0].first)
            assertTrue(requests[0].second.contains("\"suffix\":"))
            assertTrue(requests[0].second.contains("\"max_tokens\":123"))
            assertTrue(requests[1].first.endsWith("/responses"))
            assertEquals(
                "priority",
                JsonSupport.json
                    .parseToJsonElement(requests[1].second)
                    .jsonObject["service_tier"]
                    ?.jsonPrimitive
                    ?.content,
                requests[1].second,
            )
        } finally {
            upstream.stop(0)
        }
    }

    @Test
    fun reportsConfiguredTimeoutWithoutStartingAServer() = runTest {
        val provider =
            object : SubscriptionProxyProvider {
                override val id = "slow"
                override val displayName = "Slow"

                override fun isConfigured() = true

                override fun models() =
                    listOf(
                        SubscriptionProxyModel(
                            "slow-model",
                            "slow-model",
                            id,
                            displayName,
                            "openai",
                            setOf(SubscriptionProxyRoute.CHAT_COMPLETIONS),
                        )
                    )

                override suspend fun handle(ctx: ProxyCall, request: SubscriptionProxyRequest) {
                    delay(10_000)
                }
            }
        val result =
            CompletionsFimTester.test(
                SubscriptionModelCatalog(listOf(provider)),
                CompletionsConfig(modelLocalId = "slow-model", timeoutMillis = 1_000),
            )
        assertFalse(result.ok)
        assertEquals("Timed out", result.status)
    }

    @Test
    fun readsChoicesText() {
        val raw =
            """{"id":"cmpl-1","object":"text_completion","choices":[{"text":"a + b","index":0}]}"""
        assertEquals("a + b", CompletionsFimTester.completionText(raw))
    }

    @Test
    fun returnsNullWhenChoicesMissing() {
        assertNull(CompletionsFimTester.completionText("{\"error\":true}"))
    }

    @Test
    fun successHasSampleInsertAndAssembledFunction() {
        val result = CompletionsFimTester.formatSuccess("a + b", elapsedMs = 842)

        assertTrue(result.ok)
        assertEquals("Fill-in looks usable", result.status)
        assertEquals(842L, result.elapsedMs)
        assertEquals("fun add(a: Int, b: Int): Int {\n    return |\n}", result.sample)
        assertEquals("a + b", result.insert)
        assertEquals("fun add(a: Int, b: Int): Int {\n    return a + b\n}", result.assembled)
    }

    @Test
    fun emptyInsertIsNotOk() {
        val result = CompletionsFimTester.formatSuccess("", elapsedMs = 12)
        assertFalse(result.ok)
        assertEquals("Empty insert", result.status)
        assertEquals(12L, result.elapsedMs)
        assertNull(result.insert)
        assertTrue(result.detail.orEmpty().contains("no insert text"))
    }

    @Test
    fun duplicatedSuffixAndWrongAnswersAreNotReportedAsUsable() {
        for (insert in listOf("a + b\n}", "return a + b", "a - b", "42", " ", "\n\t")) {
            val result = CompletionsFimTester.formatSuccess(insert, elapsedMs = 12)
            assertFalse(result.ok, insert)
            assertTrue(result.detail.orEmpty().isNotBlank(), insert)
        }
    }

    @Test
    fun acceptsEquivalentAdditionFormatting() {
        for (insert in listOf("a+b", "b + a", "(a + b)", "a + b;", " a + b\n")) {
            assertTrue(CompletionsFimTester.formatSuccess(insert, elapsedMs = 12).ok, insert)
        }
    }
}
