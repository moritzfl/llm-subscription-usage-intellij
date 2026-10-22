package de.moritzf.quota.opencode.proxy

import com.sun.net.httpserver.HttpServer
import de.moritzf.proxy.fim.CompletionsConfig
import de.moritzf.proxy.server.JsonHelper
import de.moritzf.proxy.subscription.SubscriptionProxyRoute
import de.moritzf.proxy.subscription.SubscriptionProxyServer
import de.moritzf.quota.opencode.OpenCodeQuotaException
import de.moritzf.quota.opencode.OpenCodeTestServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OpenCodeZenSubscriptionProxyProviderTest {
    @Test
    fun logoutDisablesProxyAndDoesNotReuseCachedModels() {
        Upstream().use { upstream ->
            var session: OpenCodeConsoleSession? =
                OpenCodeConsoleSession("account", "oauth", "org_a") { null }
            val provider =
                OpenCodeZenSubscriptionProxyProvider(
                    consoleSessionProvider = { session },
                    consoleEndpoint = upstream.endpoint,
                )
            assertTrue(provider.isConfigured())
            assertEquals(3, provider.models().size)
            session = null
            assertFalse(provider.isConfigured())
            assertTrue(provider.models().isEmpty())
            assertNull(
                provider.fallbackModel(
                    "oc-chat",
                    de.moritzf.proxy.subscription.SubscriptionProxyRoute.CHAT_COMPLETIONS,
                )
            )
            assertEquals(1, upstream.requests.size)
        }
    }

    @Test
    fun unavailableConsoleDoesNotBreakCatalogForOtherProviders() {
        OpenCodeTestServer { 403 to "{}" }
            .use { server ->
                val provider =
                    OpenCodeZenSubscriptionProxyProvider(
                        consoleSessionProvider = {
                            OpenCodeConsoleSession("account", "oauth", "org_a") { null }
                        },
                        consoleEndpoint = server.endpoint,
                    )
                assertTrue(provider.isConfigured())
                assertEquals(emptyList(), provider.models())
            }
        val expired =
            OpenCodeZenSubscriptionProxyProvider(
                consoleSessionProvider = { throw OpenCodeQuotaException("Sign in again", 401) }
            )
        assertFalse(expired.isConfigured())
        assertEquals(emptyList(), expired.models())
    }

    @Test
    fun consoleOnlyLoginDiscoversModelsAndRefreshesInferenceToken() {
        Upstream(rejectInference = true).use { upstream ->
            var token = "oauth-access"
            val refreshed = mutableListOf<String>()
            val provider =
                OpenCodeZenSubscriptionProxyProvider(
                    consoleSessionProvider = {
                        OpenCodeConsoleSession("account", token, "org_a") { stale ->
                            refreshed += stale
                            token = "rotated"
                            token
                        }
                    },
                    consoleEndpoint = upstream.endpoint,
                )
            withProxy(provider) { port ->
                val models = get(port, "/v1/models")
                assertEquals(200, models.statusCode(), models.body())
                val ids =
                    JsonHelper.JSON.parseToJsonElement(models.body())
                        .jsonObject["data"]!!
                        .jsonArray
                        .map { it.jsonObject["id"]!!.jsonPrimitive.content }
                assertEquals(listOf("oc-zen-chat", "oc-zen-gpt", "oc-zen-claude"), ids)
                val config = upstream.requests.single()
                assertEquals("/console/api/v2/config", config.path)
                assertEquals("Bearer oauth-access", config.authorization)
                assertEquals("org_a", config.organization)
                assertEquals("org_a", config.inferenceOrganization)
                val chat =
                    post(
                        port,
                        "/chat/completions",
                        """{"model":"oc-chat","messages":[{"role":"user","content":"hi"}]}""",
                    )
                assertEquals(200, chat.statusCode(), chat.body())
                val attempts = upstream.requests.filter { it.path.endsWith("/chat/completions") }
                assertEquals(
                    listOf("Bearer oauth-access", "Bearer rotated"),
                    attempts.map { it.authorization },
                )
                assertTrue(attempts.all { it.inferenceOrganization == "org_a" })
                assertTrue(attempts.all { it.cookie == null })
                assertEquals(
                    "chat-native",
                    JsonHelper.JSON.parseToJsonElement(attempts.last().body)
                        .jsonObject["model"]
                        ?.jsonPrimitive
                        ?.content,
                )
                assertEquals(listOf("oauth-access"), refreshed)
            }
        }
    }

    @Test
    fun honorsNativeResponsesAndAnthropicRoutesAndBridgesChat() {
        Upstream().use { upstream ->
            val provider =
                OpenCodeZenSubscriptionProxyProvider(
                    consoleSessionProvider = {
                        OpenCodeConsoleSession("account", "oauth", "org_a") { null }
                    },
                    consoleEndpoint = upstream.endpoint,
                )
            withProxy(provider) { port ->
                for (model in listOf("oc-zen-gpt", "oc-zen-claude")) {
                    val chat =
                        post(
                            port,
                            "/v1/chat/completions",
                            """{"model":"$model","messages":[{"role":"user","content":"hi"}]}""",
                        )
                    assertEquals(200, chat.statusCode(), chat.body())
                    val body = JsonHelper.JSON.parseToJsonElement(chat.body()).jsonObject
                    assertEquals("chat.completion", body["object"]?.jsonPrimitive?.content)
                    assertTrue(chat.body().contains("hello"), chat.body())
                }
                val responses =
                    post(
                        port,
                        "/responses",
                        """{"model":"oc-zen-gpt","input":"hello","stream":true}""",
                    )
                assertEquals(200, responses.statusCode())
                assertTrue(responses.body().contains("response.completed"))
                val messages =
                    post(
                        port,
                        "/v1/messages",
                        """{"model":"oc-zen-claude","max_tokens":5,"messages":[{"role":"user","content":"hi"}]}""",
                    )
                assertEquals(200, messages.statusCode())
                assertTrue(messages.body().contains("\"type\":\"message\""), messages.body())
                val calls = upstream.requests.filterNot { it.path.endsWith("config") }
                assertEquals(
                    listOf(
                        "/inference/openai/v1/responses",
                        "/inference/anthropic/v1/messages",
                        "/inference/openai/v1/responses",
                        "/inference/anthropic/v1/messages",
                    ),
                    calls.map { it.path },
                )
                assertTrue(
                    calls.all {
                        it.inferenceOrganization == "org_a" && it.authorization == "Bearer oauth"
                    }
                )
                assertTrue(calls[0].body.contains("\"input\""))
                assertTrue(calls[1].body.contains("\"max_tokens\""))
            }
        }
    }

    @Test
    fun adaptsClaudeCodeCompletionsThroughGoAndZenChatBridges() {
        Upstream().use { upstream ->
            val provider =
                OpenCodeZenSubscriptionProxyProvider(
                    consoleSessionProvider = {
                        OpenCodeConsoleSession("account", "oauth", "org_a") { null }
                    },
                    consoleEndpoint = upstream.endpoint,
                    pools = { OpenCodePools(go = setOf("claude")) },
                )
            for (pool in listOf("go", "zen")) {
                withProxy(
                    provider,
                    CompletionsConfig(
                        enabled = true,
                        modelLocalId = "oc-$pool-claude",
                        minIntervalMillis = 0,
                    ),
                ) { port ->
                    for (stream in listOf(false, true)) {
                        val response =
                            post(
                                port,
                                "/v1/completions",
                                """{"model":"qwen2.5-coder","prompt":"val greeting = \"","suffix":"\"\n","max_tokens":32,"stop":["STOP_FIM"],"stream":$stream}""",
                            )
                        assertEquals(200, response.statusCode(), response.body())
                        val chunks =
                            if (stream) {
                                assertTrue(
                                    response.body().endsWith("data: [DONE]\n\n"),
                                    response.body(),
                                )
                                response
                                    .body()
                                    .lineSequence()
                                    .filter { it.startsWith("data: {") }
                                    .map {
                                        JsonHelper.JSON.parseToJsonElement(
                                                it.removePrefix("data: ")
                                            )
                                            .jsonObject
                                    }
                                    .toList()
                            } else {
                                listOf(
                                    JsonHelper.JSON.parseToJsonElement(response.body()).jsonObject
                                )
                            }
                        assertTrue(
                            chunks.all { it["object"]!!.jsonPrimitive.content == "text_completion" }
                        )
                        val choices = chunks.map { it["choices"]!!.jsonArray.single().jsonObject }
                        assertEquals(
                            "hello",
                            choices.joinToString("") { it["text"]!!.jsonPrimitive.content },
                        )
                        val inference = upstream.requests.last()
                        val expectedPath =
                            if (pool == "go") "/inference/go/anthropic/v1/messages"
                            else "/inference/anthropic/v1/messages"
                        assertEquals(expectedPath, inference.path)
                        assertEquals("Bearer oauth", inference.authorization)
                        assertEquals("org_a", inference.inferenceOrganization)
                        val body = JsonHelper.JSON.parseToJsonElement(inference.body).jsonObject
                        assertEquals("claude", body["model"]!!.jsonPrimitive.content)
                        assertEquals("32", body["max_tokens"]!!.jsonPrimitive.content)
                        assertEquals(stream.toString(), body["stream"]!!.jsonPrimitive.content)
                        assertTrue(
                            body["system"]!!.jsonPrimitive.content.contains("exact text to insert")
                        )
                        assertTrue(
                            body["stop_sequences"]!!.jsonArray.any {
                                it.jsonPrimitive.content == "STOP_FIM"
                            }
                        )
                    }
                }
            }
        }
    }

    @Test
    fun config401RefreshAndAccountSwitchCannotReuseAnotherOrganizationsCatalog() {
        Upstream(rejectConfig = true).use { upstream ->
            var account = "a"
            val refreshCalls = AtomicInteger()
            val provider =
                OpenCodeZenSubscriptionProxyProvider(
                    consoleSessionProvider = {
                        OpenCodeConsoleSession(account, "oauth-$account", "org_$account") {
                            refreshCalls.incrementAndGet()
                            "refreshed-$account"
                        }
                    },
                    consoleEndpoint = upstream.endpoint,
                )
            assertEquals(3, provider.models().size)
            assertEquals(1, refreshCalls.get())
            account = "b"
            assertEquals(3, provider.models().size)
            val requests = upstream.requests
            assertEquals(listOf("org_a", "org_a", "org_b"), requests.map { it.organization })
            assertEquals(
                listOf("Bearer oauth-a", "Bearer refreshed-a", "Bearer oauth-b"),
                requests.map { it.authorization },
            )
            assertNull(
                provider.fallbackModel(
                    "oc-disabled",
                    de.moritzf.proxy.subscription.SubscriptionProxyRoute.CHAT_COMPLETIONS,
                )
            )
            assertFalse(provider.models().any { it.localId == "oc-disabled" })
        }
    }

    private fun withProxy(
        provider: OpenCodeZenSubscriptionProxyProvider,
        completionsConfig: CompletionsConfig = CompletionsConfig(),
        block: (Int) -> Unit,
    ) {
        val port = ServerSocket(0).use { it.localPort }
        val proxy =
            SubscriptionProxyServer(
                port = port,
                localApiKeyProvider = { "local" },
                providers = { listOf(provider) },
                completionsConfig = { completionsConfig },
            )
        try {
            proxy.start()
            block(port)
        } finally {
            proxy.stop(gracePeriodMillis = 0)
        }
    }

    private fun get(port: Int, path: String) =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .header("Authorization", "Bearer local")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun post(
        port: Int,
        path: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ) =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .header("Authorization", "Bearer local")
                .header("Content-Type", "application/json")
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun consoleGoProviderKeepsItsOwnUrlAndPackage() {
        val body =
            """{"providers":{
            "opencode":{"package":"aisdk:@ai-sdk/openai-compatible","settings":{"baseURL":"https://opencode.ai/inference/openai/v1"},"models":{
                "deepseek-v4.1-flash":{},
                "qwen3.6-plus":{"package":"aisdk:@ai-sdk/anthropic","settings":{"baseURL":"https://opencode.ai/inference/anthropic/v1"}}
            }},
            "opencode-go":{"package":"aisdk:@ai-sdk/openai-compatible","settings":{"baseURL":"https://opencode.ai/inference/go/openai/v1"},"models":{
                "deepseek-v4.1-flash":{},
                "hy3":{},
                "minimax-m3":{"package":"aisdk:@ai-sdk/anthropic","settings":{"baseURL":"https://opencode.ai/inference/go/anthropic/v1"}}
            }}
        }}"""
        val models =
            OpenCodeConsoleModel.parse(
                    body,
                    OpenCodePools(
                        go = setOf("deepseek-v4.1-flash", "qwen3.6-plus", "hy3"),
                        zen = setOf("deepseek-v4.1-flash"),
                    ),
                )
                .associateBy { it.model.localId }

        assertEquals(
            "https://opencode.ai/inference/openai/v1",
            models["oc-zen-deepseek-v4.1-flash"]!!.baseUri.toString(),
        )
        assertEquals(
            "https://opencode.ai/inference/go/openai/v1",
            models["oc-go-deepseek-v4.1-flash"]!!.baseUri.toString(),
        )
        assertEquals(
            "https://opencode.ai/inference/anthropic/v1",
            models["oc-zen-qwen3.6-plus"]!!.baseUri.toString(),
        )
        assertEquals(
            SubscriptionProxyRoute.ANTHROPIC_MESSAGES,
            models["oc-zen-qwen3.6-plus"]!!.nativeRoute,
        )
        assertEquals(
            "https://opencode.ai/inference/go/anthropic/v1",
            models["oc-go-minimax-m3"]!!.baseUri.toString(),
        )
        assertEquals(
            SubscriptionProxyRoute.ANTHROPIC_MESSAGES,
            models["oc-go-minimax-m3"]!!.nativeRoute,
        )
        assertTrue("oc-go-hy3" in models)
        assertFalse("oc-go-qwen3.6-plus" in models)
        assertFalse(
            models.keys.any {
                it.startsWith("oc-") && !it.startsWith("oc-go-") && !it.startsWith("oc-zen-")
            }
        )
        assertEquals(5, models.size)
    }

    @Test
    fun goProviderRoutesToInferenceGoAndOldPrefixPrefersGo() {
        Upstream(includeGoProvider = true).use { upstream ->
            val provider =
                OpenCodeZenSubscriptionProxyProvider(
                    consoleSessionProvider = {
                        OpenCodeConsoleSession("account", "oauth", "org_a") { null }
                    },
                    consoleEndpoint = upstream.endpoint,
                    pools = { OpenCodePools(go = setOf("chat", "mini")) },
                )
            withProxy(provider) { port ->
                val ids =
                    JsonHelper.JSON.parseToJsonElement(get(port, "/v1/models").body())
                        .jsonObject["data"]!!
                        .jsonArray
                        .map { it.jsonObject["id"]!!.jsonPrimitive.content }
                assertTrue("oc-zen-chat" in ids, ids.toString())
                assertTrue("oc-go-chat" in ids, ids.toString())
                assertTrue("oc-go-mini" in ids, ids.toString())
                assertFalse("oc-chat" in ids)
                val go =
                    post(
                        port,
                        "/v1/chat/completions",
                        """{"model":"oc-go-chat","messages":[{"role":"user","content":"hi"}]}""",
                    )
                assertEquals(200, go.statusCode(), go.body())
                val mini =
                    post(
                        port,
                        "/v1/chat/completions",
                        """{"model":"oc-go-mini","messages":[{"role":"user","content":"hi"}]}""",
                    )
                assertEquals(200, mini.statusCode(), mini.body())
                val legacy =
                    post(
                        port,
                        "/v1/chat/completions",
                        """{"model":"oc-chat","messages":[{"role":"user","content":"hi"}]}""",
                    )
                assertEquals(200, legacy.statusCode(), legacy.body())
                val calls = upstream.requests.filter { !it.path.endsWith("/config") }
                assertEquals(
                    listOf(
                        "/inference/go/openai/v1/chat/completions",
                        "/inference/go/anthropic/v1/messages",
                        "/inference/go/openai/v1/chat/completions",
                    ),
                    calls.map { it.path },
                )
            }
        }
    }

    @Test
    fun goInferenceKeepsClientSessionOrPromptCacheKey() {
        Upstream(includeGoProvider = true).use { upstream ->
            val provider =
                OpenCodeZenSubscriptionProxyProvider(
                    consoleSessionProvider = {
                        OpenCodeConsoleSession("account", "oauth", "org_a") { null }
                    },
                    consoleEndpoint = upstream.endpoint,
                )
            withProxy(provider) { port ->
                val kept =
                    post(
                        port,
                        "/v1/chat/completions",
                        """{"model":"oc-go-chat","messages":[{"role":"user","content":"hi"}]}""",
                        mapOf("x-opencode-session" to "ses_keep"),
                    )
                assertEquals(200, kept.statusCode(), kept.body())
                val cached =
                    post(
                        port,
                        "/v1/chat/completions",
                        """{"model":"oc-go-chat","prompt_cache_key":"lsu-fim-chat-v2","messages":[{"role":"user","content":"hi"}]}""",
                    )
                assertEquals(200, cached.statusCode(), cached.body())
                val calls = upstream.requests.filter { it.path.endsWith("/chat/completions") }
                assertEquals(listOf("ses_keep", "lsu-fim-chat-v2"), calls.map { it.session })
            }
        }
    }

    @Test
    fun zenConsoleUrlAlsoAdvertisesGoAndOldPrefixPrefersGo() {
        Upstream().use { upstream ->
            val provider =
                OpenCodeZenSubscriptionProxyProvider(
                    consoleSessionProvider = {
                        OpenCodeConsoleSession("account", "oauth", "org_a") { null }
                    },
                    consoleEndpoint = upstream.endpoint,
                    pools = { OpenCodePools(go = setOf("chat")) },
                )
            withProxy(provider) { port ->
                val ids =
                    JsonHelper.JSON.parseToJsonElement(get(port, "/v1/models").body())
                        .jsonObject["data"]!!
                        .jsonArray
                        .map { it.jsonObject["id"]!!.jsonPrimitive.content }
                assertTrue("oc-zen-chat" in ids, ids.toString())
                assertTrue("oc-go-chat" in ids, ids.toString())
                assertFalse("oc-chat" in ids)
                val go =
                    post(
                        port,
                        "/v1/chat/completions",
                        """{"model":"oc-go-chat","messages":[{"role":"user","content":"hi"}]}""",
                    )
                assertEquals(200, go.statusCode(), go.body())
                val legacy =
                    post(
                        port,
                        "/v1/chat/completions",
                        """{"model":"oc-chat","messages":[{"role":"user","content":"hi"}]}""",
                    )
                assertEquals(200, legacy.statusCode(), legacy.body())
                val calls = upstream.requests.filter { it.path.endsWith("/chat/completions") }
                assertEquals(
                    listOf(
                        "/inference/go/openai/v1/chat/completions",
                        "/inference/go/openai/v1/chat/completions",
                    ),
                    calls.map { it.path },
                )
                assertTrue(
                    calls.all {
                        !it.session.isNullOrBlank() &&
                            it.userAgent?.startsWith("llm-subscription-usage/") == true
                    }
                )
            }
        }
    }

    @Test
    fun poolsAndTwinUrlsCoverZenAndInferenceBaseUrls() {
        fun uri(value: String) = URI.create(value)
        assertEquals(
            OpenCodePool.ZEN,
            OpenCodeConsoleModel.poolOf(uri("https://opencode.ai/zen/v1")),
        )
        assertEquals(
            OpenCodePool.GO,
            OpenCodeConsoleModel.poolOf(uri("https://opencode.ai/zen/go/v1")),
        )
        assertEquals(
            OpenCodePool.GO,
            OpenCodeConsoleModel.poolOf(uri("https://opencode.ai/inference/go/anthropic/v1")),
        )
        assertNull(OpenCodeConsoleModel.poolOf(uri("https://opencode.ai/citizen/v1")))
        assertEquals(
            uri("https://opencode.ai/zen/go/v1"),
            OpenCodeConsoleModel.rewritePoolBase(
                uri("https://opencode.ai/zen/v1"),
                OpenCodePool.GO,
            ),
        )
        assertEquals(
            uri("https://opencode.ai/zen/v1"),
            OpenCodeConsoleModel.rewritePoolBase(
                uri("https://opencode.ai/zen/go/v1"),
                OpenCodePool.ZEN,
            ),
        )
        assertEquals(
            uri("https://opencode.ai/inference/go/openai/v1"),
            OpenCodeConsoleModel.rewritePoolBase(
                uri("https://opencode.ai/inference/openai/v1"),
                OpenCodePool.GO,
            ),
        )
        assertNull(
            OpenCodeConsoleModel.rewritePoolBase(
                uri("https://opencode.ai/zen/go/v1"),
                OpenCodePool.GO,
            )
        )
    }

    @Test
    fun consoleZenBaseUrlsAreAdvertisedWhateverTheProviderId() {
        Upstream(zenProviderId = "opencode-zen", zenBasePath = "zen/v1").use { upstream ->
            val provider =
                OpenCodeZenSubscriptionProxyProvider(
                    consoleSessionProvider = {
                        OpenCodeConsoleSession("account", "oauth", "org_a") { null }
                    },
                    consoleEndpoint = upstream.endpoint,
                    pools = { OpenCodePools(go = setOf("chat")) },
                )
            withProxy(provider) { port ->
                val ids =
                    JsonHelper.JSON.parseToJsonElement(get(port, "/v1/models").body())
                        .jsonObject["data"]!!
                        .jsonArray
                        .map { it.jsonObject["id"]!!.jsonPrimitive.content }
                assertTrue("oc-zen-chat" in ids && "oc-zen-gpt" in ids, ids.toString())
                assertTrue("oc-go-chat" in ids, ids.toString())

                val go =
                    post(
                        port,
                        "/v1/chat/completions",
                        """{"model":"oc-go-chat","messages":[{"role":"user","content":"hi"}]}""",
                    )
                assertEquals(200, go.statusCode(), go.body())
                assertEquals(
                    listOf("/zen/go/v1/chat/completions"),
                    upstream.requests
                        .filter { it.path.endsWith("/chat/completions") }
                        .map { it.path },
                )
            }
        }
    }

    @Test
    fun geminiConsoleModelProxiesGenerateContentAndStreams() {
        Upstream(includeGoogle = true).use { upstream ->
            val provider =
                OpenCodeZenSubscriptionProxyProvider(
                    consoleSessionProvider = {
                        OpenCodeConsoleSession("account", "oauth", "org_a") { null }
                    },
                    consoleEndpoint = upstream.endpoint,
                )
            withProxy(provider) { port ->
                val ids =
                    JsonHelper.JSON.parseToJsonElement(get(port, "/v1/models").body())
                        .jsonObject["data"]!!
                        .jsonArray
                        .map { it.jsonObject["id"]!!.jsonPrimitive.content }
                assertTrue("oc-zen-gemini" in ids, ids.toString())

                val chat =
                    post(
                        port,
                        "/v1/chat/completions",
                        """{"model":"oc-zen-gemini","messages":[{"role":"system","content":"You are Junie"},{"role":"user","content":"hi"}],"tools":[{"type":"function","function":{"name":"submit","description":"Submit","parameters":{"type":"object","properties":{}}}}],"tool_choice":"auto","max_tokens":64,"reasoning_effort":"low"}""",
                    )
                assertEquals(200, chat.statusCode(), chat.body())
                val chatBody = JsonHelper.JSON.parseToJsonElement(chat.body()).jsonObject
                assertEquals("chat.completion", chatBody["object"]!!.jsonPrimitive.content)
                assertEquals(
                    "hello",
                    chatBody["choices"]!!
                        .jsonArray
                        .first()
                        .jsonObject["message"]!!
                        .jsonObject["content"]!!
                        .jsonPrimitive
                        .content,
                )
                val generate = upstream.requests.last { it.path.contains(":generateContent") }
                assertEquals(
                    "/inference/google/v1beta/models/gemini-3.8-flash:generateContent",
                    generate.path,
                )
                assertEquals("Bearer oauth", generate.authorization)
                assertEquals("org_a", generate.inferenceOrganization)
                val upstreamBody = JsonHelper.JSON.parseToJsonElement(generate.body).jsonObject
                assertEquals(
                    "You are Junie",
                    upstreamBody["systemInstruction"]!!
                        .jsonObject["parts"]!!
                        .jsonArray
                        .first()
                        .jsonObject["text"]!!
                        .jsonPrimitive
                        .content,
                )
                assertEquals(
                    "hi",
                    upstreamBody["contents"]!!
                        .jsonArray
                        .first()
                        .jsonObject["parts"]!!
                        .jsonArray
                        .first()
                        .jsonObject["text"]!!
                        .jsonPrimitive
                        .content,
                )
                assertEquals(
                    "submit",
                    upstreamBody["tools"]!!
                        .jsonArray
                        .first()
                        .jsonObject["functionDeclarations"]!!
                        .jsonArray
                        .first()
                        .jsonObject["name"]!!
                        .jsonPrimitive
                        .content,
                )
                assertEquals(
                    "64",
                    upstreamBody["generationConfig"]!!
                        .jsonObject["maxOutputTokens"]!!
                        .jsonPrimitive
                        .content,
                )
                assertEquals(
                    "low",
                    upstreamBody["generationConfig"]!!
                        .jsonObject["thinkingConfig"]!!
                        .jsonObject["thinkingLevel"]!!
                        .jsonPrimitive
                        .content,
                )

                val streamed =
                    post(
                        port,
                        "/v1/chat/completions",
                        """{"model":"oc-zen-gemini","stream":true,"messages":[{"role":"user","content":"hi"}]}""",
                    )
                assertEquals(200, streamed.statusCode(), streamed.body())
                assertTrue(streamed.body().contains("chat.completion.chunk"), streamed.body())
                assertTrue(streamed.body().contains("hel"), streamed.body())
                assertTrue(streamed.body().contains("lo"), streamed.body())
                assertTrue(streamed.body().endsWith("data: [DONE]\n\n"), streamed.body())
                val streamCall =
                    upstream.requests.last { it.path.contains(":streamGenerateContent") }
                assertEquals(
                    "/inference/google/v1beta/models/gemini-3.8-flash:streamGenerateContent",
                    streamCall.path,
                )
            }
        }
    }

    private class Upstream(
        private var rejectInference: Boolean = false,
        private var rejectConfig: Boolean = false,
        private val includeGoProvider: Boolean = false,
        private val includeGoogle: Boolean = false,
        private val zenProviderId: String = "opencode",
        private val zenBasePath: String = "inference/openai/v1",
    ) : AutoCloseable {
        data class Request(
            val path: String,
            val authorization: String?,
            val organization: String?,
            val inferenceOrganization: String?,
            val cookie: String?,
            val body: String,
            val session: String? = null,
            val userAgent: String? = null,
        )

        val requests = CopyOnWriteArrayList<Request>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val endpoint = URI.create("http://127.0.0.1:${server.address.port}/console/")

        init {
            server.createContext("/") { exchange ->
                val path = exchange.requestURI.path
                val body = exchange.requestBody.bufferedReader().readText()
                val headers = exchange.requestHeaders
                requests +=
                    Request(
                        path,
                        headers.getFirst("Authorization"),
                        headers.getFirst("x-org-id"),
                        headers.getFirst("x-opencode-org-id"),
                        headers.getFirst("Cookie"),
                        body,
                        headers.getFirst("x-opencode-session"),
                        headers.getFirst("User-Agent"),
                    )
                val config = path.endsWith("/config")
                val reject =
                    if (config) rejectConfig.also { rejectConfig = false }
                    else rejectInference.also { rejectInference = false }
                val responses = path.endsWith("/responses")
                val messagesStream = path.endsWith("/messages") && body.contains("\"stream\":true")
                val googleStream = path.contains(":streamGenerateContent")
                val googleSync = path.contains(":generateContent") && !googleStream
                val result =
                    when {
                        reject -> """{"error":"unauthorized"}"""
                        config -> config(headers.getFirst("x-org-id"))
                        googleStream ->
                            "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"hel\"}]}}]}\n\n" +
                                "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"lo\"}]}}]}\n\n" +
                                "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[]},\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":3,\"candidatesTokenCount\":2,\"totalTokenCount\":5}}\n\n"
                        googleSync ->
                            """{"candidates":[{"content":{"role":"model","parts":[{"text":"hello"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":2,"totalTokenCount":5}}"""
                        responses ->
                            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}\n\n" +
                                "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"hello\"}]}],\"usage\":{\"input_tokens\":1,\"output_tokens\":2,\"total_tokens\":3}}}\n\ndata: [DONE]\n\n"
                        messagesStream ->
                            "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[]}}\n\n" +
                                "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"hel\"}}\n\n" +
                                "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"lo\"}}\n\n" +
                                "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\n" +
                                "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
                        path.endsWith("/messages") ->
                            """{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"text","text":"hello"}],"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":2}}"""
                        else ->
                            """{"id":"chat_1","object":"chat.completion","choices":[{"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}]}"""
                    }
                exchange.responseHeaders.set(
                    "Content-Type",
                    if (((responses || messagesStream || googleStream) && !reject))
                        "text/event-stream"
                    else "application/json",
                )
                val bytes = result.toByteArray()
                exchange.sendResponseHeaders(if (reject) 401 else 200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        private fun config(org: String?): String {
            val google =
                if (!includeGoogle) ""
                else
                    ""","gemini":{
                "modelID":"gemini-3.8-flash",
                "package":"@opencode/ai/providers/google",
                "settings":{"baseURL":"http://127.0.0.1:${server.address.port}/inference/google/v1beta"},
                "capabilities":{"tools":true,"input":["text","image","pdf"],"output":["text"]},
                "limit":{"context":1048576,"output":65536}}"""
            val go =
                if (!includeGoProvider) ""
                else
                    ""","opencode-go":{
                "package":"aisdk:@ai-sdk/openai-compatible",
                "settings":{"baseURL":"http://127.0.0.1:${server.address.port}/inference/go/openai/v1"},
                "headers":{"x-opencode-org-id":"$org"},"models":{
                    "chat":{},
                    "mini":{"package":"aisdk:@ai-sdk/anthropic","settings":{"baseURL":"http://127.0.0.1:${server.address.port}/inference/go/anthropic/v1"}}
                }}"""
            return """{"providers":{"$zenProviderId":{
            "package":"aisdk:@ai-sdk/openai-compatible",
            "settings":{"baseURL":"http://127.0.0.1:${server.address.port}/$zenBasePath"},
            "headers":{"x-opencode-org-id":"$org"},"models":{
                "chat":{"modelID":"chat-native","capabilities":{"tools":true,"input":["text"],"output":["text"]}},
                "gpt":{"package":"aisdk:@ai-sdk/openai","limit":{"context":100000,"output":10000}},
                "claude":{"package":"aisdk:@ai-sdk/anthropic","settings":{"baseURL":"http://127.0.0.1:${server.address.port}/inference/anthropic/v1"}},
                "disabled":{"disabled":true}$google
            }}$go}}"""
        }

        override fun close() = server.stop(0)
    }

    companion object {
        private val client = HttpClient.newHttpClient()
    }
}
