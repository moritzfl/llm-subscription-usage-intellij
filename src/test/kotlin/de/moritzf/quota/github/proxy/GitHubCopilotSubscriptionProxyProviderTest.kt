package de.moritzf.quota.github.proxy

import com.sun.net.httpserver.HttpServer
import de.moritzf.proxy.server.JsonHelper
import de.moritzf.proxy.subscription.SubscriptionProxyServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class GitHubCopilotSubscriptionProxyProviderTest {
    @Test
    fun advertisesCopilotModelsWithGhPrefix() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = get(proxy.port, "/v1/models")

                assertEquals(200, response.statusCode())
                val ids = JsonHelper.JSON.parseToJsonElement(response.body()).jsonObject["data"]!!.jsonArray
                    .map { it.jsonObject["id"]!!.jsonPrimitive.content }
                assertEquals(
                    listOf(
                        "gh-claude-haiku-4.5",
                        "gh-claude-sonnet-4.5",
                        "gh-claude-sonnet-4.6",
                        "gh-gemini-2.5-pro",
                        "gh-gemini-3-flash-preview",
                        "gh-gemini-3.1-pro-preview",
                        "gh-gemini-3.5-flash",
                        "gh-gpt-5-mini",
                        "gh-gpt-5.3-codex",
                        "gh-gpt-5.4",
                        "gh-gpt-5.4-mini",
                        "gh-mai-code-1-flash-picker",
                    ),
                    ids,
                )
                assertFalse("gh-gpt-5.5" in ids)
                val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/models", request.path)
                assertEquals("Bearer github-token", request.firstHeader("Authorization"))
                assertEquals("2026-06-01", request.firstHeader("X-GitHub-Api-Version"))
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun rewritesPrefixedModelAndAddsCopilotHeaders() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = post(
                    proxy.port,
                    "/v1/chat/completions",
                    "{\"model\":\"gh-gpt-5-mini\",\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,abc\"}}]}]}",
                    bearer = true,
                )

                assertEquals(200, response.statusCode())
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS)) // /models discovery
                val inference = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/chat/completions", inference.path)
                assertEquals("Bearer github-token", inference.firstHeader("Authorization"))
                assertEquals("conversation-edits", inference.firstHeader("Openai-Intent"))
                assertEquals("true", inference.firstHeader("Copilot-Vision-Request"))
                assertTrue(inference.body.contains("\"model\":\"gpt-5-mini\""), inference.body)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun normalizesCopilotChatCompletionStreamChunks() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = post(
                    proxy.port,
                    "/v1/chat/completions",
                    "{\"model\":\"gh-gpt-5-mini\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                    bearer = true,
                )

                assertEquals(200, response.statusCode())
                assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"))
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS)) // /models discovery
                val inference = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/chat/completions", inference.path)
                assertTrue(inference.body.contains("\"stream\":true"), inference.body)
                assertTrue(response.body().contains("\"object\":\"chat.completion.chunk\""), response.body())
                assertTrue(response.body().contains("\"model\":\"gpt-5-mini\""), response.body())
                assertTrue(response.body().contains("\"choices\""), response.body())
                assertTrue(response.body().contains("data: [DONE]"), response.body())
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun forwardsPrefixedModelsMissingFromDiscovery() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = post(
                    proxy.port,
                    "/v1/responses",
                    "{\"model\":\"gh-gpt-5.5\",\"input\":\"hi\"}",
                    bearer = true,
                )

                assertEquals(200, response.statusCode())
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS)) // /models discovery
                val inference = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/responses", inference.path)
                assertTrue(inference.body.contains("\"model\":\"gpt-5.5\""), inference.body)
                assertFalse(inference.body.contains("gh-gpt-5.5"), inference.body)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun forwardsOpenCodeNamespacedModels() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = post(
                    proxy.port,
                    "/v1/responses",
                    "{\"model\":\"github-copilot/gpt-5.4-mini\",\"input\":\"hi\"}",
                    bearer = true,
                )

                assertEquals(200, response.statusCode())
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS)) // /models discovery
                val inference = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/responses", inference.path)
                assertTrue(inference.body.contains("\"model\":\"gpt-5.4-mini\""), inference.body)
                assertFalse(inference.body.contains("github-copilot/gpt-5.4-mini"), inference.body)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun rejectsAnthropicModelsOnOpenAiChatCompletionsRoute() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = post(
                    proxy.port,
                    "/v1/chat/completions",
                    "{\"model\":\"gh-claude-sonnet-4.6\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                    bearer = true,
                )

                assertEquals(400, response.statusCode())
                assertTrue(response.body().contains("does not support /chat/completions"), response.body())
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS)) // /models discovery only
                assertEquals(null, upstream.requests.poll(500, TimeUnit.MILLISECONDS))
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun acceptsAnthropicStyleLocalApiKeyForMessagesRoute() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = post(
                    proxy.port,
                    "/v1/messages",
                    "{\"model\":\"gh-claude-sonnet-4.6\",\"output_config\":{\"effort\":\"high\"},\"thinking\":{\"type\":\"adaptive\"},\"context_management\":{\"edits\":[]},\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                    bearer = false,
                    extraHeaders = mapOf("anthropic-beta" to "effort-2025-11-24"),
                )

                assertEquals(200, response.statusCode())
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS)) // /models discovery
                val inference = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/v1/messages", inference.path)
                assertEquals("Bearer github-token", inference.firstHeader("Authorization"))
                assertEquals(null, inference.firstHeader("anthropic-beta"))
                assertTrue(inference.body.contains("\"model\":\"claude-sonnet-4.6\""), inference.body)
                assertFalse(inference.body.contains("output_config"), inference.body)
                assertFalse(inference.body.contains("thinking"), inference.body)
                assertFalse(inference.body.contains("context_management"), inference.body)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun modelInfoAdvertisesClaudeCompatibleMessagesEndpoint() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = get(proxy.port, "/v1/model/info")

                assertEquals(200, response.statusCode())
                val data = JsonHelper.JSON.parseToJsonElement(response.body()).jsonObject["data"]!!.jsonArray
                val claudeInfo = data.first { it.jsonObject["id"]!!.jsonPrimitive.content == "gh-claude-sonnet-4.6" }
                    .jsonObject["model_info"]!!.jsonObject
                val endpoints = claudeInfo["supported_endpoints"]!!.jsonArray.map { it.jsonPrimitive.content }
                assertTrue("/v1/messages" in endpoints)
                assertFalse("/v1/chat/completions" in endpoints)
                assertTrue(claudeInfo["supports_anthropic_messages"]!!.jsonPrimitive.content.toBoolean())

                val gptInfo = data.first { it.jsonObject["id"]!!.jsonPrimitive.content == "gh-gpt-5.4-mini" }
                    .jsonObject["model_info"]!!.jsonObject
                val gptEndpoints = gptInfo["supported_endpoints"]!!.jsonArray.map { it.jsonPrimitive.content }
                assertTrue("/v1/responses" in gptEndpoints)
                assertTrue("/v1/chat/completions" in gptEndpoints)
                assertFalse(gptInfo["supports_anthropic_messages"]!!.jsonPrimitive.content.toBoolean())
            } finally {
                proxy.stop()
            }
        }
    }

    private fun newProxy(upstreamBaseUri: URI): TestProxy {
        val port = freePort()
        val provider = GitHubCopilotSubscriptionProxyProvider(
            accessTokenProvider = { "github-token" },
            upstreamBaseUri = upstreamBaseUri,
            requestLogDir = Files.createTempDirectory("github-subscription-proxy-test-logs").toString(),
        )
        return TestProxy(
            port,
            SubscriptionProxyServer(
                port = port,
                localApiKeyProvider = { "local-key" },
                providers = { listOf(provider) },
                requestLogDir = Files.createTempDirectory("subscription-proxy-test-logs").toString(),
            ),
        )
    }

    private fun get(port: Int, path: String): HttpResponse<String> {
        return httpClient.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .header("Authorization", "Bearer local-key")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    private fun post(
        port: Int,
        path: String,
        body: String,
        bearer: Boolean,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .header("Content-Type", "application/json")
        extraHeaders.forEach { (name, value) -> builder.header(name, value) }
        if (bearer) {
            builder.header("Authorization", "Bearer local-key")
        } else {
            builder.header("x-api-key", "local-key")
        }
        return httpClient.send(
            builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    private data class TestProxy(val port: Int, val server: SubscriptionProxyServer) {
        fun start() = server.start()
        fun stop() = server.stop()
    }

    private class TestUpstream : AutoCloseable {
        val requests = LinkedBlockingQueue<CapturedRequest>()
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val baseUri: URI

        init {
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                requests += CapturedRequest(
                    path = exchange.requestURI.rawPath,
                    headers = exchange.requestHeaders.mapValues { it.value.toList() },
                    body = body,
                )
                val responseBody = if (exchange.requestURI.rawPath == "/models") {
                    "{\"data\":[" + copilotModels.joinToString(",") + "]}"
                } else if (body.contains("\"stream\":true")) {
                    "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"hi\"},\"index\":0}]}\n\n" +
                        "data: {\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}\n\n" +
                        "data: [DONE]\n\n"
                } else {
                    "{\"id\":\"ok\",\"choices\":[]}"
                }
                val response = responseBody.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set(
                    "Content-Type",
                    if (body.contains("\"stream\":true")) "text/event-stream" else "application/json",
                )
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { output -> output.write(response) }
            }
            server.start()
            baseUri = URI.create("http://127.0.0.1:${server.address.port}")
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
            return headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value
                ?.firstOrNull()
        }
    }

    private fun freePort(): Int {
        ServerSocket(0).use { socket ->
            socket.reuseAddress = true
            return socket.localPort
        }
    }

    companion object {
        private val httpClient: HttpClient = HttpClient.newHttpClient()

        // Local fixture only: production discovers models from GitHub Copilot's /models endpoint.
        private val copilotModels = listOf(
            copilotModel("claude-haiku-4.5", family = "claude-haiku", endpoints = listOf("/v1/messages")),
            copilotModel("claude-sonnet-4.5", family = "claude-sonnet", endpoints = listOf("/v1/messages")),
            copilotModel("claude-sonnet-4.6", family = "claude-sonnet", endpoints = listOf("/chat/completions", "/v1/messages")),
            copilotModel("gemini-2.5-pro", family = "gemini-pro"),
            copilotModel("gemini-3-flash-preview", family = "gemini-flash"),
            copilotModel("gemini-3.1-pro-preview", family = "gemini-pro"),
            copilotModel("gemini-3.5-flash", family = "gemini-flash"),
            copilotModel("gpt-5-mini", family = "gpt-mini"),
            copilotModel("gpt-5.3-codex", family = "gpt-codex", endpoints = listOf("/responses")),
            copilotModel("gpt-5.4", family = "gpt", endpoints = listOf("/responses")),
            copilotModel("gpt-5.4-mini", family = "gpt-mini", endpoints = listOf("/responses")),
            copilotModel("mai-code-1-flash-picker", family = "mai"),
            copilotModel("gpt-5.5", family = "gpt", pickerEnabled = false, endpoints = listOf("/responses")),
            copilotModel("disabled-model", family = "test", disabled = true),
            copilotModel("missing-output", family = "test", maxOutputTokens = null),
            copilotModel("missing-tool-calls", family = "test", toolCalls = null),
        )

        private fun copilotModel(
            id: String,
            family: String,
            endpoints: List<String> = listOf("/chat/completions"),
            pickerEnabled: Boolean = true,
            disabled: Boolean = false,
            toolCalls: Boolean? = true,
            maxOutputTokens: Int? = 64_000,
        ): String {
            val endpointsJson = endpoints.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
            val policy = if (disabled) ",\"policy\":{\"state\":\"disabled\"}" else ""
            val limits = listOfNotNull(
                "\"max_context_window_tokens\":128000",
                "\"max_prompt_tokens\":128000",
                maxOutputTokens?.let { "\"max_output_tokens\":$it" },
            ).joinToString(",")
            val supports = listOfNotNull(
                toolCalls?.let { "\"tool_calls\":$it" },
                "\"vision\":true",
                "\"reasoning_effort\":[\"medium\",\"high\"]",
            ).joinToString(",")
            return "{" +
                "\"model_picker_enabled\":$pickerEnabled," +
                "\"id\":\"$id\"," +
                "\"name\":\"$id\"," +
                "\"version\":\"$id-2026-01-01\"," +
                "\"supported_endpoints\":$endpointsJson" +
                policy + "," +
                "\"capabilities\":{" +
                "\"family\":\"$family\"," +
                "\"limits\":{$limits}," +
                "\"supports\":{$supports}" +
                "}}"
        }
    }
}
