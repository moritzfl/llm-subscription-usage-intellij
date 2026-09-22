package de.moritzf.quota.opencode.proxy

import com.sun.net.httpserver.HttpServer
import de.moritzf.proxy.server.JsonHelper
import de.moritzf.proxy.subscription.SubscriptionProxyServer
import de.moritzf.quota.opencode.OpenCodeTestServer
import de.moritzf.quota.opencode.OpenCodeQuotaException
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

class OpenCodeZenSubscriptionProxyProviderTest {
    @Test
    fun logoutDisablesProxyAndDoesNotReuseCachedModels() {
        Upstream().use { upstream ->
            var session: OpenCodeConsoleSession? = OpenCodeConsoleSession("account", "oauth", "org_a") { null }
            val provider = OpenCodeZenSubscriptionProxyProvider(
                consoleSessionProvider = { session },
                consoleEndpoint = upstream.endpoint,
            )
            assertTrue(provider.isConfigured())
            assertEquals(3, provider.models().size)
            session = null
            assertFalse(provider.isConfigured())
            assertTrue(provider.models().isEmpty())
            assertNull(provider.fallbackModel("oc-chat", de.moritzf.proxy.subscription.SubscriptionProxyRoute.CHAT_COMPLETIONS))
            assertEquals(1, upstream.requests.size)
        }
    }

    @Test
    fun unavailableConsoleDoesNotBreakCatalogForOtherProviders() {
        OpenCodeTestServer { 403 to "{}" }.use { server ->
            val provider = OpenCodeZenSubscriptionProxyProvider(
                consoleSessionProvider = { OpenCodeConsoleSession("account", "oauth", "org_a") { null } },
                consoleEndpoint = server.endpoint,
            )
            assertTrue(provider.isConfigured())
            assertEquals(emptyList(), provider.models())
        }
        val expired = OpenCodeZenSubscriptionProxyProvider(consoleSessionProvider = {
            throw OpenCodeQuotaException("Sign in again", 401)
        })
        assertFalse(expired.isConfigured())
        assertEquals(emptyList(), expired.models())
    }

    @Test
    fun consoleOnlyLoginDiscoversModelsAndRefreshesInferenceToken() {
        Upstream(rejectInference = true).use { upstream ->
            var token = "oauth-access"
            val refreshed = mutableListOf<String>()
            val provider = OpenCodeZenSubscriptionProxyProvider(
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
                val ids = JsonHelper.JSON.parseToJsonElement(models.body()).jsonObject["data"]!!.jsonArray
                    .map { it.jsonObject["id"]!!.jsonPrimitive.content }
                assertEquals(listOf("oc-chat", "oc-gpt", "oc-claude"), ids)
                val config = upstream.requests.single()
                assertEquals("/console/api/v2/config", config.path)
                assertEquals("Bearer oauth-access", config.authorization)
                assertEquals("org_a", config.organization)
                val chat = post(port, "/chat/completions", """{"model":"oc-chat","messages":[{"role":"user","content":"hi"}]}""")
                assertEquals(200, chat.statusCode(), chat.body())
                val attempts = upstream.requests.filter { it.path.endsWith("/chat/completions") }
                assertEquals(listOf("Bearer oauth-access", "Bearer rotated"), attempts.map { it.authorization })
                assertTrue(attempts.all { it.inferenceOrganization == "org_a" })
                assertTrue(attempts.all { it.cookie == null })
                assertEquals("chat-native", JsonHelper.JSON.parseToJsonElement(attempts.last().body).jsonObject["model"]?.jsonPrimitive?.content)
                assertEquals(listOf("oauth-access"), refreshed)
            }
        }
    }

    @Test
    fun honorsNativeResponsesAndAnthropicRoutesAndBridgesChat() {
        Upstream().use { upstream ->
            val provider = OpenCodeZenSubscriptionProxyProvider(
                consoleSessionProvider = { OpenCodeConsoleSession("account", "oauth", "org_a") { null } },
                consoleEndpoint = upstream.endpoint,
            )
            withProxy(provider) { port ->
                for (model in listOf("oc-gpt", "oc-claude")) {
                    val chat = post(port, "/v1/chat/completions", """{"model":"$model","messages":[{"role":"user","content":"hi"}]}""")
                    assertEquals(200, chat.statusCode(), chat.body())
                    val body = JsonHelper.JSON.parseToJsonElement(chat.body()).jsonObject
                    assertEquals("chat.completion", body["object"]?.jsonPrimitive?.content)
                    assertTrue(chat.body().contains("hello"), chat.body())
                }
                val responses = post(port, "/responses", """{"model":"oc-gpt","input":"hello","stream":true}""")
                assertEquals(200, responses.statusCode())
                assertTrue(responses.body().contains("response.completed"))
                val messages = post(port, "/v1/messages", """{"model":"oc-claude","max_tokens":5,"messages":[{"role":"user","content":"hi"}]}""")
                assertEquals(200, messages.statusCode())
                assertTrue(messages.body().contains("\"type\":\"message\""), messages.body())
                val calls = upstream.requests.filterNot { it.path.endsWith("config") }
                assertEquals(listOf("/inference/openai/v1/responses", "/inference/anthropic/v1/messages",
                    "/inference/openai/v1/responses", "/inference/anthropic/v1/messages"), calls.map { it.path })
                assertTrue(calls.all { it.inferenceOrganization == "org_a" && it.authorization == "Bearer oauth" })
                assertTrue(calls[0].body.contains("\"input\""))
                assertTrue(calls[1].body.contains("\"max_tokens\""))
            }
        }
    }

    @Test
    fun config401RefreshAndAccountSwitchCannotReuseAnotherOrganizationsCatalog() {
        Upstream(rejectConfig = true).use { upstream ->
            var account = "a"
            val refreshCalls = AtomicInteger()
            val provider = OpenCodeZenSubscriptionProxyProvider(
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
            assertEquals(listOf("Bearer oauth-a", "Bearer refreshed-a", "Bearer oauth-b"), requests.map { it.authorization })
            assertNull(provider.fallbackModel("oc-disabled", de.moritzf.proxy.subscription.SubscriptionProxyRoute.CHAT_COMPLETIONS))
            assertFalse(provider.models().any { it.localId == "oc-disabled" })
        }
    }

    private fun withProxy(provider: OpenCodeZenSubscriptionProxyProvider, block: (Int) -> Unit) {
        val port = ServerSocket(0).use { it.localPort }
        val proxy = SubscriptionProxyServer(port = port, localApiKeyProvider = { "local" }, providers = { listOf(provider) })
        try {
            proxy.start()
            block(port)
        } finally {
            proxy.stop()
        }
    }

    private fun get(port: Int, path: String) = client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).header("Authorization", "Bearer local").GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun post(port: Int, path: String, body: String) = client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .header("Authorization", "Bearer local").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString(),
    )

    private class Upstream(private var rejectInference: Boolean = false, private var rejectConfig: Boolean = false) : AutoCloseable {
        data class Request(val path: String, val authorization: String?, val organization: String?, val inferenceOrganization: String?, val cookie: String?, val body: String)
        val requests = CopyOnWriteArrayList<Request>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val endpoint = URI.create("http://127.0.0.1:${server.address.port}/console/")
        init {
            server.createContext("/") { exchange ->
                val path = exchange.requestURI.path
                val body = exchange.requestBody.bufferedReader().readText()
                val headers = exchange.requestHeaders
                requests += Request(path, headers.getFirst("Authorization"), headers.getFirst("x-org-id"), headers.getFirst("x-opencode-org-id"), headers.getFirst("Cookie"), body)
                val config = path.endsWith("/config")
                val reject = if (config) rejectConfig.also { rejectConfig = false } else rejectInference.also { rejectInference = false }
                val responses = path.endsWith("/responses")
                val result = when {
                    reject -> """{"error":"unauthorized"}"""
                    config -> config(headers.getFirst("x-org-id"))
                    responses -> "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}\n\n" +
                        "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"hello\"}]}],\"usage\":{\"input_tokens\":1,\"output_tokens\":2,\"total_tokens\":3}}}\n\ndata: [DONE]\n\n"
                    path.endsWith("/messages") -> """{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"text","text":"hello"}],"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":2}}"""
                    else -> """{"id":"chat_1","object":"chat.completion","choices":[{"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}]}"""
                }
                exchange.responseHeaders.set("Content-Type", if (responses && !reject) "text/event-stream" else "application/json")
                val bytes = result.toByteArray()
                exchange.sendResponseHeaders(if (reject) 401 else 200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        private fun config(org: String?): String = """{"providers":{"opencode":{
            "package":"aisdk:@ai-sdk/openai-compatible",
            "settings":{"baseURL":"http://127.0.0.1:${server.address.port}/inference/openai/v1"},
            "headers":{"x-opencode-org-id":"$org"},"models":{
                "chat":{"modelID":"chat-native","capabilities":{"tools":true,"input":["text"],"output":["text"]}},
                "gpt":{"package":"aisdk:@ai-sdk/openai","limit":{"context":100000,"output":10000}},
                "claude":{"package":"aisdk:@ai-sdk/anthropic","settings":{"baseURL":"http://127.0.0.1:${server.address.port}/inference/anthropic/v1"}},
                "disabled":{"disabled":true}
            }}}}"""

        override fun close() = server.stop(0)
    }

    companion object {
        private val client = HttpClient.newHttpClient()
    }
}
