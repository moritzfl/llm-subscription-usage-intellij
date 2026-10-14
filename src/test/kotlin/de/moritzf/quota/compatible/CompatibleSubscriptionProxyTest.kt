package de.moritzf.quota.compatible

import com.sun.net.httpserver.HttpServer
import de.moritzf.proxy.fim.CompletionsConfig
import de.moritzf.proxy.server.JsonHelper
import de.moritzf.proxy.subscription.SubscriptionProxyProvider
import de.moritzf.proxy.subscription.SubscriptionProxyRoute
import de.moritzf.proxy.subscription.SubscriptionProxyServer
import de.moritzf.quota.compatible.proxy.CompatibleSubscriptionProxyProvider
import de.moritzf.quota.idea.common.IndependentAccountProxyProvider
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.settings.ProviderAccount
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class CompatibleSubscriptionProxyTest {
    @Test
    fun liteLlmResponsesPreserveMultimodalToolsAndStreamingAcrossBothRoutes() {
        Upstream().use { upstream ->
            upstream.chatBody =
                """{"id":"resp_1","object":"response","status":"completed","output":[{"type":"function_call","call_id":"call_1","name":"lookup","arguments":"{}"}]}"""
            upstream.streamBody =
                "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"Hello\"}\n\nevent: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"status\":\"completed\"}}\n\n"
            val provider =
                CompatibleSubscriptionProxyProvider(
                    "gateway",
                    "Gateway",
                    CompatibleEndpoint.parse(upstream.baseUrl, "company-chat").copy(liteLlm = true),
                    { "virtual-key" },
                )
            withProxy(provider) { port ->
                for (route in listOf("/responses", "/v1/responses")) {
                    for (stream in listOf(false, true)) {
                        val body =
                            """{"model":"api-gateway/company-chat","stream":$stream,"input":[{"role":"user","content":[{"type":"input_text","text":"Read both"},{"type":"input_image","image_url":"data:image/png;base64,cG5n"},{"type":"input_file","filename":"sample.pdf","file_data":"data:application/pdf;base64,cGRm"}]}],"tools":[{"type":"function","name":"lookup","parameters":{"type":"object","properties":{}}}],"tool_choice":"auto"}"""
                        val response = post(port, route, body)
                        assertEquals(200, response.statusCode(), response.body())
                        assertEquals(
                            if (stream) upstream.streamBody else upstream.chatBody,
                            response.body(),
                        )
                        val sent = upstream.requests.last()
                        assertEquals("/custom%20api/v1/responses", sent.path)
                        assertEquals("Bearer virtual-key", sent.auth)
                        val payload = parse(sent.body)
                        assertEquals("company-chat", payload["model"]!!.jsonPrimitive.content)
                        for (field in listOf("input", "tools", "tool_choice", "stream")) {
                            assertEquals(parse(body)[field], payload[field])
                        }
                    }
                }
            }
        }
    }

    @Test
    fun imageAndNativePdfInputsReachOnlyTheSelectedConnectionAndPublishOptedInCapabilities() {
        Upstream().use { upstream ->
            val provider =
                CompatibleSubscriptionProxyProvider(
                    "one",
                    "Images",
                    CompatibleEndpoint.parse(upstream.baseUrl, "vendor/codestral")
                        .copy(visionModel = "vendor/codestral", documentModel = "vendor/codestral"),
                    { "upstream-key" },
                )
            withProxy(provider) { port ->
                val content =
                    """[{"type":"text","text":"Read both"},{"type":"image_url","image_url":{"url":"data:image/png;base64,cG5n"}},{"type":"file","file":{"filename":"sample.pdf","file_data":"data:application/pdf;base64,cGRm"}}]"""
                for (stream in listOf(false, true)) {
                    val request =
                        """{"model":"api-one/vendor/codestral","stream":$stream,"messages":[{"role":"user","content":$content}]}"""
                    assertEquals(200, post(port, "/v1/chat/completions", request).statusCode())
                    assertEquals(
                        parse(request)["messages"],
                        parse(upstream.requests.last().body)["messages"],
                    )
                    assertEquals("Bearer upstream-key", upstream.requests.last().auth)
                }
                val info =
                    parse(get(port, "/v1/model/info").body())["data"]!!
                        .jsonArray
                        .single()
                        .jsonObject["model_info"]!!
                        .jsonObject
                assertEquals("true", info["supports_vision"]!!.jsonPrimitive.content)
                assertEquals("true", info["supports_pdf_input"]!!.jsonPrimitive.content)
            }
        }
    }

    @Test
    fun toolCallResponsesAndToolResultMessagesPassThroughUnchanged() {
        Upstream().use { upstream ->
            upstream.chatBody =
                """{"id":"call","choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"lookup","arguments":"{\"q\":\"hello\"}"}}]},"finish_reason":"tool_calls"}]}"""
            upstream.streamBody =
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n"
            withProxy(upstream.provider("one")) { port ->
                assertEquals(
                    upstream.chatBody,
                    post(port, "/chat/completions", chat("api-one/vendor/codestral")).body(),
                )
                val followUp =
                    """{"model":"api-one/vendor/codestral","stream":true,"messages":[{"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"lookup","arguments":"{}"}}]},{"role":"tool","tool_call_id":"call_1","content":"result"}]}"""
                assertEquals(
                    upstream.streamBody,
                    post(port, "/v1/chat/completions", followUp).body(),
                )
                assertEquals(
                    parse(followUp)["messages"],
                    parse(upstream.requests.last().body)["messages"],
                )
            }
        }
    }

    @Test
    fun discoveryAndModelTestUseDraftEndpointAndBearerKey() {
        Upstream().use { upstream ->
            val provider = upstream.provider("one", manual = "")
            assertEquals(listOf("vendor/codestral"), provider.discoverModels())
            assertEquals("a + b", provider.testModel("vendor/codestral"))
            assertEquals(
                listOf("/custom%20api/v1/models", "/custom%20api/v1/chat/completions"),
                upstream.requests.map { it.path },
            )
            assertTrue(upstream.requests.all { it.auth == "Bearer upstream-key" })
            assertEquals(
                "vendor/codestral",
                parse(upstream.requests.last().body)["model"]!!.jsonPrimitive.content,
            )
        }
    }

    @Test
    fun missingDiscoveryHasActionableErrorButManualModelsAndInferenceStillWork() {
        Upstream().use { upstream ->
            upstream.discoveryStatus = 404
            val provider = upstream.provider("manual")
            val failure = assertFailsWith<IllegalStateException> { provider.discoverModels() }
            assertTrue(failure.message!!.contains("enter model IDs manually"))
            assertFalse(failure.message!!.contains("secret-error-body"))
            upstream.requests.clear()
            assertEquals("vendor/codestral", provider.models().single().upstreamId)
            assertTrue(upstream.requests.isEmpty(), "Manual models must not require /models")
            assertEquals("a + b", provider.testModel("vendor/codestral"))
            assertNull(
                provider.fallbackModel(
                    "api-manual/not-allowed",
                    SubscriptionProxyRoute.CHAT_COMPLETIONS,
                )
            )
        }
    }

    @Test
    fun surfacesAuthInvalidJsonAndEmptyDiscoveryWithoutCallingThemSuccessfulInference() {
        Upstream().use { upstream ->
            val provider = upstream.provider("one", manual = "")
            for (status in listOf(401, 403, 429, 500)) {
                upstream.discoveryStatus = status
                val failure = assertFailsWith<IllegalStateException> { provider.discoverModels() }
                assertTrue(failure.message!!.contains(status.toString()))
                assertFalse(failure.message!!.contains("secret-error-body"))
            }
            upstream.discoveryStatus = 200
            upstream.discoveryBody = "<html>login</html>"
            assertFailsWith<IllegalStateException> { provider.discoverModels() }
            upstream.discoveryBody = "{}"
            assertFailsWith<IllegalStateException> { provider.discoverModels() }
            upstream.discoveryBody = "{\"data\":[]}"
            assertTrue(provider.discoverModels().isEmpty())
            upstream.chatBody = "{\"choices\":[]}"
            assertFailsWith<IllegalStateException> { provider.testModel("vendor/codestral") }
        }
    }

    @Test
    fun failedDiscoveryIsCachedAndKeyRotationInvalidatesCatalog() {
        Upstream().use { upstream ->
            var key = "first-key"
            val provider = upstream.provider("one", manual = "", key = { key })
            upstream.discoveryStatus = 503
            repeat(3) { assertTrue(provider.models().isEmpty()) }
            assertEquals(1, upstream.requests.size)
            upstream.discoveryStatus = 200
            key = "second-key"
            assertEquals("vendor/codestral", provider.models().single().upstreamId)
            assertEquals(2, upstream.requests.size)
            assertEquals("Bearer second-key", upstream.requests.last().auth)
            upstream.discoveryBody = "{\"data\":[{\"id\":\"different-model\"}]}"
            key = "third-key"
            assertEquals("different-model", provider.models().single().upstreamId)
        }
    }

    @Test
    fun independentEndpointsKeepOverlappingModelsToolsAndStreamingIsolated() {
        Upstream().use { first ->
            Upstream().use { second ->
                val a = account("a", "First", first)
                val b = account("b", "Second", second)
                var accounts = listOf(a, b)
                val provider =
                    IndependentAccountProxyProvider(
                        QuotaProviderType.OPENAI_COMPATIBLE,
                        { accounts },
                        { account ->
                            CompatibleSubscriptionProxyProvider(
                                account.id,
                                account.name,
                                CompatibleEndpoint.parse(
                                    account.extra(ProviderAccount.EXTRA_COMPATIBLE_BASE_URL)!!,
                                    "vendor/codestral",
                                ),
                                { "key-${account.id}" },
                            )
                        },
                    )
                withProxy(provider) { port ->
                    val models = parse(get(port, "/v1/models").body())["data"]!!.jsonArray
                    assertEquals(
                        setOf("api-a/vendor/codestral", "api-b/vendor/codestral"),
                        models.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet(),
                    )
                    val tools =
                        parse(
                            """{"tools":[{"type":"function","function":{"name":"lookup","parameters":{"type":"object","properties":{}}}}],"tool_choice":"auto","parallel_tool_calls":true}"""
                        )
                    for ((id, target) in listOf("a" to first, "b" to second)) {
                        for (stream in listOf(false, true)) {
                            val body = buildJsonObject {
                                put("model", "api-$id/vendor/codestral")
                                put("stream", stream)
                                put(
                                    "messages",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("role", "user")
                                                put("content", "Hello")
                                            }
                                        )
                                    },
                                )
                                tools.forEach { (k, v) -> put(k, v) }
                            }
                            val response =
                                post(
                                    port,
                                    if (stream) "/chat/completions" else "/v1/chat/completions",
                                    body.toString(),
                                )
                            assertEquals(200, response.statusCode(), response.body())
                            assertEquals(if (stream) SSE else CHAT, response.body())
                            val sent = parse(target.requests.last().body)
                            assertEquals("vendor/codestral", sent["model"]!!.jsonPrimitive.content)
                            tools.forEach { (k, v) -> assertEquals(v, sent[k]) }
                            assertEquals("Bearer key-$id", target.requests.last().auth)
                        }
                    }
                    val callsToSecond = second.requests.size
                    first.chatStatus = 429
                    assertEquals(
                        429,
                        post(port, "/v1/chat/completions", chat("api-a/vendor/codestral"))
                            .statusCode(),
                    )
                    assertEquals(
                        callsToSecond,
                        second.requests.size,
                        "A rate limit must never fall through to another endpoint",
                    )
                    accounts = listOf(a.copy(name = "Renamed"), b)
                    assertEquals("api-a/vendor/codestral", provider.models().first().localId)
                    assertTrue(provider.models().first().providerName.contains("Renamed"))
                    accounts = listOf(b)
                    assertEquals(
                        400,
                        post(port, "/v1/chat/completions", chat("api-a/vendor/codestral"))
                            .statusCode(),
                    )
                    assertEquals(callsToSecond, second.requests.size)
                }
            }
        }
    }

    @Test
    fun endpointEditsInvalidateDiscoveryWithoutRenamingModelNamespace() {
        Upstream().use { first ->
            Upstream().use { second ->
                var row = account("a", "Endpoint", first)
                second.discoveryBody = "{\"data\":[{\"id\":\"new-model\"}]}"
                val provider =
                    IndependentAccountProxyProvider(
                        QuotaProviderType.OPENAI_COMPATIBLE,
                        { listOf(row) },
                        { account ->
                            CompatibleSubscriptionProxyProvider(
                                account.id,
                                account.name,
                                CompatibleEndpoint.parse(
                                    account.extra(ProviderAccount.EXTRA_COMPATIBLE_BASE_URL)!!
                                ),
                                { "key" },
                            )
                        },
                    )
                assertEquals("api-a/vendor/codestral", provider.models().single().localId)
                row = account("a", "Endpoint", second)
                assertEquals("api-a/new-model", provider.models().single().localId)
                assertEquals(1, first.requests.size)
                assertEquals(1, second.requests.size)
            }
        }
    }

    @Test
    fun metadataDoesNotInventToolsVisionNativeFimResponsesOrFreeUsage() {
        Upstream().use { upstream ->
            withProxy(upstream.provider("one")) { port ->
                val info =
                    parse(get(port, "/v1/model/info").body())["data"]!!
                        .jsonArray
                        .single()
                        .jsonObject["model_info"]!!
                        .jsonObject
                for (unknown in
                    listOf(
                        "supports_function_calling",
                        "supports_tool_choice",
                        "supports_vision",
                        "supports_parallel_function_calling",
                        "supports_prompt_caching",
                        "input_cost_per_token",
                        "output_cost_per_token",
                    )) assertFalse(unknown in info, unknown)
                assertEquals("false", info["supports_native_fim"]!!.jsonPrimitive.content)
                assertEquals(
                    listOf("/v1/chat/completions"),
                    info["supported_endpoints"]!!.jsonArray.map { it.jsonPrimitive.content },
                )
                assertEquals(
                    400,
                    post(
                            port,
                            "/v1/responses",
                            "{\"model\":\"api-one/vendor/codestral\",\"input\":\"Hello\"}",
                        )
                        .statusCode(),
                )
                assertTrue(upstream.requests.isEmpty())
            }
        }
    }

    @Test
    fun genericCodestralAndDeepSeekUseChatFimForJsonAndSseWithoutProviderWorkarounds() {
        Upstream().use { upstream ->
            for (model in listOf("vendor/codestral", "deepseek-v4")) {
                withProxy(upstream.provider("one", manual = model), "api-one/$model") { port ->
                    for (stream in listOf(false, true)) {
                        val response =
                            post(
                                port,
                                "/v1/completions",
                                """{"model":"qwen2.5-coder","prompt":"function add(a, b) { return ","suffix":"; }","stream":$stream}""",
                            )
                        assertEquals(200, response.statusCode(), response.body())
                        assertTrue(response.body().contains("a + b"), response.body())
                        if (stream) assertTrue(response.body().endsWith("data: [DONE]\n\n"))
                        val sent = parse(upstream.requests.last().body)
                        assertEquals(model, sent["model"]!!.jsonPrimitive.content)
                        assertTrue("messages" in sent)
                        assertFalse("reasoning_effort" in sent)
                        assertFalse("service_tier" in sent)
                        assertEquals(
                            "/custom%20api/v1/chat/completions",
                            upstream.requests.last().path,
                        )
                    }
                }
            }
        }
    }

    private fun account(id: String, name: String, upstream: Upstream) =
        ProviderAccount(id, QuotaProviderType.OPENAI_COMPATIBLE.id, name).apply {
            setExtra(ProviderAccount.EXTRA_COMPATIBLE_BASE_URL, upstream.baseUrl)
        }

    private fun withProxy(
        provider: SubscriptionProxyProvider,
        fim: String = "",
        block: (Int) -> Unit,
    ) {
        val port = ServerSocket(0).use { it.localPort }
        val proxy =
            SubscriptionProxyServer(
                port,
                { "local-key" },
                { listOf(provider) },
                completionsConfig = {
                    if (fim.isEmpty()) CompletionsConfig.DISABLED
                    else
                        CompletionsConfig(enabled = true, modelLocalId = fim, minIntervalMillis = 0)
                },
            )
        proxy.start()
        try {
            block(port)
        } finally {
            proxy.stop(gracePeriodMillis = 0)
        }
    }

    private fun get(port: Int, path: String) = send(port, path, null)

    private fun post(port: Int, path: String, body: String) = send(port, path, body)

    private fun send(port: Int, path: String, body: String?): HttpResponse<String> {
        val request =
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
                .header("Authorization", "Bearer local-key")
        if (body == null) request.GET()
        else
            request
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
        return HttpClient.newHttpClient().use {
            it.send(request.build(), HttpResponse.BodyHandlers.ofString())
        }
    }

    private class Upstream : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val executor = Executors.newCachedThreadPool()
        val baseUrl
            get() = "http://127.0.0.1:${server.address.port}/custom%20api/v1"

        val requests = CopyOnWriteArrayList<Captured>()
        var discoveryStatus = 200
        var discoveryBody = "{\"data\":[{\"id\":\"vendor/codestral\"}]}"
        var chatStatus = 200
        var chatBody = CHAT
        var streamBody = SSE

        init {
            server.executor = executor
            server.createContext("/") { exchange ->
                val path = exchange.requestURI.rawPath
                val body = exchange.requestBody.bufferedReader().use { it.readText() }
                requests += Captured(path, exchange.requestHeaders.getFirst("Authorization"), body)
                val models = path.endsWith("/models")
                val stream =
                    body.isNotBlank() && parse(body)["stream"]?.jsonPrimitive?.content == "true"
                val status = if (models) discoveryStatus else chatStatus
                val payload =
                    if (status != 200) "{\"error\":{\"message\":\"secret-error-body\"}}"
                    else if (models) discoveryBody else if (stream) streamBody else chatBody
                exchange.responseHeaders.set(
                    "Content-Type",
                    if (stream) "text/event-stream" else "application/json",
                )
                val bytes = payload.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        fun provider(
            id: String,
            manual: String = "vendor/codestral",
            key: () -> String? = { "upstream-key" },
        ) =
            CompatibleSubscriptionProxyProvider(
                id,
                id,
                CompatibleEndpoint.parse(baseUrl, manual),
                key,
            )

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private data class Captured(val path: String, val auth: String?, val body: String)

    companion object {
        private const val CHAT =
            """{"id":"chat-1","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"a + b"},"finish_reason":"stop"}]}"""
        private const val SSE =
            "data: {\"id\":\"chat-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"a + b\"},\"finish_reason\":null}]}\n\ndata: {\"id\":\"chat-1\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"

        private fun parse(body: String) = JsonHelper.JSON.parseToJsonElement(body).jsonObject

        private fun chat(model: String) =
            """{"model":"$model","messages":[{"role":"user","content":"Hello"}]}"""
    }
}
