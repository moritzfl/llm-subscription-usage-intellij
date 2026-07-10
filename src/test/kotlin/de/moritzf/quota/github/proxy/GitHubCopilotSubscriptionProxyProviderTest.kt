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
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes
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
    fun advertisesOpenAiCompatibleCopilotModelsWithGhPrefix() {
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
                assertEquals("vscode-chat", request.firstHeader("Copilot-Integration-Id"))
                assertEquals("vscode/1.104.1", request.firstHeader("Editor-Version"))
                assertEquals("copilot-chat/0.26.7", request.firstHeader("Editor-Plugin-Version"))
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
                assertEquals("vscode-chat", inference.firstHeader("Copilot-Integration-Id"))
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
                    "{\"model\":\"gh-gpt-6.0\",\"input\":\"hi\"}",
                    bearer = true,
                )

                assertEquals(200, response.statusCode())
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS)) // /models discovery
                val inference = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/responses", inference.path)
                assertTrue(inference.body.contains("\"model\":\"gpt-6.0\""), inference.body)
                assertFalse(inference.body.contains("gh-gpt-6.0"), inference.body)
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
    fun omitsResponseOutputLimitForCopilotGptModels() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = post(
                    proxy.port,
                    "/v1/responses",
                    "{\"model\":\"gh-gpt-5.4-mini\",\"input\":\"hi\",\"max_output_tokens\":16}",
                    bearer = true,
                )

                assertEquals(200, response.statusCode())
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS)) // /models discovery
                val inference = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/responses", inference.path)
                assertTrue(inference.body.contains("\"model\":\"gpt-5.4-mini\""), inference.body)
                assertFalse(inference.body.contains("max_output_tokens"), inference.body)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun bridgesResponsesOnlyGptChatCompletionsToResponsesApi() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = post(
                    proxy.port,
                    "/v1/chat/completions",
                    "{\"model\":\"gh-gpt-5.4-mini\",\"max_tokens\":16,\"messages\":[{\"role\":\"system\",\"content\":\"be brief\"},{\"role\":\"user\",\"content\":\"hi\"}]}",
                    bearer = true,
                )

                assertEquals(200, response.statusCode())
                assertTrue(response.body().contains("\"object\":\"chat.completion\""), response.body())
                assertTrue(response.body().contains("hi from responses"), response.body())
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS)) // /models discovery
                val inference = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/responses", inference.path)
                assertEquals("Bearer github-token", inference.firstHeader("Authorization"))
                assertEquals("vscode-chat", inference.firstHeader("Copilot-Integration-Id"))
                assertEquals("conversation-edits", inference.firstHeader("Openai-Intent"))
                assertTrue(inference.body.contains("\"model\":\"gpt-5.4-mini\""), inference.body)
                assertTrue(inference.body.contains("\"input\""), inference.body)
                assertTrue(inference.body.contains("\"instructions\":\"be brief\""), inference.body)
                assertFalse(inference.body.contains("\"messages\""), inference.body)
                assertFalse(inference.body.contains("max_output_tokens"), inference.body)
                assertFalse(inference.body.contains("\"store\""), inference.body)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun bridgesResponsesOnlyGptStreamingChatCompletionsToResponsesApi() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = post(
                    proxy.port,
                    "/v1/chat/completions",
                    "{\"model\":\"gh-gpt-5.4-mini\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                    bearer = true,
                )

                assertEquals(200, response.statusCode())
                assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"))
                assertTrue(response.body().contains("\"object\":\"chat.completion.chunk\""), response.body())
                assertTrue(response.body().contains("hi from responses"), response.body())
                assertTrue(response.body().contains("data: [DONE]"), response.body())
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS)) // /models discovery
                val inference = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/responses", inference.path)
                assertTrue(inference.body.contains("\"stream\":true"), inference.body)
                assertTrue(inference.body.contains("\"model\":\"gpt-5.4-mini\""), inference.body)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun bridgesMaiResponsesOnlyChatCompletionsToResponsesApi() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = post(
                    proxy.port,
                    "/v1/chat/completions",
                    "{\"model\":\"gh-mai-code-1-flash-picker\",\"temperature\":0.2,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                    bearer = true,
                )

                assertEquals(200, response.statusCode())
                assertTrue(response.body().contains("\"object\":\"chat.completion\""), response.body())
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS)) // /models discovery
                val inference = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/responses", inference.path)
                assertTrue(inference.body.contains("\"model\":\"mai-code-1-flash-picker\""), inference.body)
                assertFalse(inference.body.contains("\"messages\""), inference.body)
                assertFalse(inference.body.contains("\"temperature\""), inference.body)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun routesAllAdvertisedGhModelsOnChatCompletions() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val modelInfoResponse = get(proxy.port, "/v1/model/info")
                assertEquals(200, modelInfoResponse.statusCode())
                val modelInfos = JsonHelper.JSON.parseToJsonElement(modelInfoResponse.body())
                    .jsonObject["data"]!!.jsonArray
                    .map { it.jsonObject }
                    .filter { it["id"]!!.jsonPrimitive.content.startsWith("gh-") }
                assertTrue(modelInfos.isNotEmpty())
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS)) // /models discovery

                for (modelInfo in modelInfos) {
                    val modelId = modelInfo["id"]!!.jsonPrimitive.content
                    val endpoints = modelInfo["model_info"]!!
                        .jsonObject["supported_endpoints"]!!
                        .jsonArray
                        .map { it.jsonPrimitive.content }
                    val expectedUpstreamPath = expectedChatUpstreamPath(modelId, endpoints)

                    val response = post(
                        proxy.port,
                        "/v1/chat/completions",
                        "{\"model\":\"$modelId\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                        bearer = true,
                    )
                    assertEquals(200, response.statusCode(), "model=$modelId body=${response.body()}")

                    val inference = nextInferenceRequest(upstream, modelId)
                    assertEquals(expectedUpstreamPath, inference.path, "model=$modelId endpoints=$endpoints")
                }
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun bridgesAnthropicModelsOnOpenAiChatCompletionsRoute() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = post(
                    proxy.port,
                    "/v1/chat/completions",
                    "{\"model\":\"gh-claude-haiku-4.5\",\"max_tokens\":16,\"messages\":[{\"role\":\"system\",\"content\":\"be brief\"},{\"role\":\"user\",\"content\":\"hi\"}]}",
                    bearer = true,
                )

                assertEquals(200, response.statusCode())
                assertTrue(response.body().contains("\"object\":\"chat.completion\""), response.body())
                assertTrue(response.body().contains("hi from claude"), response.body())
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS)) // /models discovery only
                val inference = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/v1/messages", inference.path)
                assertTrue(inference.body.contains("\"model\":\"claude-haiku-4.5\""), inference.body)
                assertTrue(inference.body.contains("\"system\":\"be brief\""), inference.body)
                assertTrue(inference.body.contains("\"max_tokens\":16"), inference.body)
                assertTrue(inference.body.contains("\"content\":\"hi\""), inference.body)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun bridgesStreamingAnthropicModelsOnOpenAiChatCompletionsRoute() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = post(
                    proxy.port,
                    "/v1/chat/completions",
                    "{\"model\":\"gh-claude-haiku-4.5\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                    bearer = true,
                )

                assertEquals(200, response.statusCode())
                assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"))
                assertTrue(response.body().contains("\"object\":\"chat.completion.chunk\""), response.body())
                assertTrue(response.body().contains("hi from claude"), response.body())
                assertTrue(response.body().contains("data: [DONE]"), response.body())
                assertFalse(response.body().contains("event: message_start"), response.body())
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS)) // /models discovery only
                val inference = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/v1/messages", inference.path)
                assertTrue(inference.body.contains("\"stream\":true"), inference.body)
                assertTrue(inference.body.contains("\"model\":\"claude-haiku-4.5\""), inference.body)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun bridgesStreamingAnthropicUsageWhenOpenAiClientRequestsIt() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = post(
                    proxy.port,
                    "/v1/chat/completions",
                    "{\"model\":\"gh-claude-haiku-4.5\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                    bearer = true,
                )

                assertEquals(200, response.statusCode())
                assertTrue(response.body().contains("\"choices\":[],\"usage\":{"), response.body())
                assertTrue(response.body().contains("\"prompt_tokens\":3"), response.body())
                assertTrue(response.body().contains("\"completion_tokens\":5"), response.body())
                assertTrue(response.body().contains("data: [DONE]"), response.body())
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS)) // /models discovery only
                val inference = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/v1/messages", inference.path)
                assertTrue(inference.body.contains("\"stream\":true"), inference.body)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun bridgesAnthropicToolCallsOnOpenAiChatCompletionsRoute() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = post(
                    proxy.port,
                    "/v1/chat/completions",
                    "{\"model\":\"gh-claude-sonnet-4.6\",\"max_tokens\":64,\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"description\":\"Get weather\",\"parameters\":{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}}}],\"tool_choice\":\"auto\",\"messages\":[{\"role\":\"user\",\"content\":\"Use the tool for Berlin weather.\"}]}",
                    bearer = true,
                )

                assertEquals(200, response.statusCode())
                assertTrue(response.body().contains("\"finish_reason\":\"tool_calls\""), response.body())
                assertTrue(response.body().contains("\"tool_calls\""), response.body())
                assertTrue(response.body().contains("\"name\":\"get_weather\""), response.body())
                val arguments = response.body().replace(" ", "")
                assertTrue(arguments.contains("\\\"city\\\":\\\"Berlin\\\""), response.body())
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS)) // /models discovery
                val inference = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/v1/messages", inference.path)
                assertTrue(inference.body.contains("\"tools\""), inference.body)
                assertTrue(inference.body.contains("\"input_schema\""), inference.body)
                assertTrue(inference.body.contains("\"tool_choice\":{\"type\":\"auto\"}"), inference.body)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun omitsAnthropicToolsWhenOpenAiToolChoiceIsNone() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = post(
                    proxy.port,
                    "/v1/chat/completions",
                    "{\"model\":\"gh-claude-sonnet-4.6\",\"max_tokens\":64,\"tool_choice\":\"none\",\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"parameters\":{\"type\":\"object\"}}}],\"messages\":[{\"role\":\"user\",\"content\":\"Say hello without tools.\"}]}",
                    bearer = true,
                )

                assertEquals(200, response.statusCode())
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS)) // /models discovery
                val inference = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/v1/messages", inference.path)
                assertFalse(inference.body.contains("\"tools\""), inference.body)
                assertFalse(inference.body.contains("\"tool_choice\""), inference.body)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun mapsLegacyOpenAiFunctionsToAnthropicTools() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = post(
                    proxy.port,
                    "/v1/chat/completions",
                    "{\"model\":\"gh-claude-sonnet-4.6\",\"max_tokens\":64,\"functions\":[{\"name\":\"get_weather\",\"description\":\"Get weather\",\"parameters\":{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}}],\"function_call\":{\"name\":\"get_weather\"},\"messages\":[{\"role\":\"user\",\"content\":\"Use the tool for Berlin weather.\"}]}",
                    bearer = true,
                )

                assertEquals(200, response.statusCode())
                assertTrue(response.body().contains("\"function_call\""), response.body())
                assertTrue(response.body().contains("\"finish_reason\":\"function_call\""), response.body())
                assertFalse(response.body().contains("\"tool_calls\""), response.body())
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS)) // /models discovery
                val inference = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/v1/messages", inference.path)
                assertTrue(inference.body.contains("\"tools\""), inference.body)
                assertTrue(inference.body.contains("\"input_schema\""), inference.body)
                assertTrue(inference.body.contains("\"tool_choice\":{\"type\":\"tool\",\"name\":\"get_weather\"}"), inference.body)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun mapsDataOpenAiImageUrlToAnthropicImageSource() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = post(
                    proxy.port,
                    "/v1/chat/completions",
                    "{\"model\":\"gh-claude-sonnet-4.6\",\"max_tokens\":64,\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"describe\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,iVBORw0KGgo=\"}}]}]}",
                    bearer = true,
                )

                assertEquals(200, response.statusCode())
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS)) // /models discovery
                val inference = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/v1/messages", inference.path)
                assertTrue(inference.body.contains("\"type\":\"image\""), inference.body)
                assertTrue(inference.body.contains("\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\""), inference.body)
                assertFalse(inference.body.contains("\"image_url\""), inference.body)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun blocksLoopbackRemoteImageUrlsForClaudeBridge() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = post(
                    proxy.port,
                    "/v1/chat/completions",
                    "{\"model\":\"gh-claude-sonnet-4.6\",\"max_tokens\":64,\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"describe\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"http://127.0.0.1:9/secret.png\"}}]}]}",
                    bearer = true,
                )

                assertEquals(200, response.statusCode())
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS)) // /models discovery
                val inference = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/v1/messages", inference.path)
                // SSRF guard drops unsafe remote images instead of server-side fetching them.
                assertFalse(inference.body.contains("\"type\":\"image\""), inference.body)
                assertFalse(inference.body.contains("secret.png"), inference.body)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun mapsOpenAiJsonResponseFormatToSystemInstruction() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = post(
                    proxy.port,
                    "/v1/chat/completions",
                    "{\"model\":\"gh-claude-sonnet-4.6\",\"max_tokens\":64,\"response_format\":{\"type\":\"json_object\"},\"messages\":[{\"role\":\"user\",\"content\":\"Return JSON.\"}]}",
                    bearer = true,
                )

                assertEquals(200, response.statusCode())
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS)) // /models discovery
                val inference = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/v1/messages", inference.path)
                assertTrue(inference.body.contains("Respond with a valid JSON object only"), inference.body)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun bridgesStreamingAnthropicToolCallsOnOpenAiChatCompletionsRoute() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = post(
                    proxy.port,
                    "/v1/chat/completions",
                    "{\"model\":\"gh-claude-sonnet-4.6\",\"stream\":true,\"max_tokens\":64,\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"parameters\":{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}}}],\"messages\":[{\"role\":\"user\",\"content\":\"Use the tool for Berlin weather.\"}]}",
                    bearer = true,
                )

                assertEquals(200, response.statusCode())
                assertTrue(response.body().contains("\"tool_calls\""), response.body())
                assertTrue(response.body().contains("\"index\":0,\"id\":\"toolu_1\""), response.body())
                assertTrue(response.body().contains("\"id\":\"toolu_1\""), response.body())
                assertTrue(response.body().contains("\"name\":\"get_weather\""), response.body())
                assertTrue(response.body().contains("\"arguments\":\"{\\\"city\\\":"), response.body())
                assertTrue(response.body().contains("\"finish_reason\":\"tool_calls\""), response.body())
                assertTrue(response.body().contains("data: [DONE]"), response.body())
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS)) // /models discovery
                val inference = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/v1/messages", inference.path)
                assertTrue(inference.body.contains("\"stream\":true"), inference.body)
                assertTrue(inference.body.contains("\"tools\""), inference.body)
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
                val claudeInfo = data.first { it.jsonObject["id"]!!.jsonPrimitive.content == "gh-claude-haiku-4.5" }
                    .jsonObject["model_info"]!!.jsonObject
                val endpoints = claudeInfo["supported_endpoints"]!!.jsonArray.map { it.jsonPrimitive.content }
                assertTrue("/v1/messages" in endpoints)
                assertTrue("/v1/chat/completions" in endpoints)
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

    @Test
    fun usesPersistedCatalogAcrossProxyRestartsBeforeEvictingMissingModels() {
        val fullCatalog = modelsBody(
            listOf(
                copilotModel("gpt-5-mini", family = "gpt-mini"),
                copilotModel("gpt-5.4-mini", family = "gpt-mini", endpoints = listOf("/responses")),
            ),
        )
        val reducedCatalog = modelsBody(listOf(copilotModel("gpt-5-mini", family = "gpt-mini")))
        val modelRequests = AtomicInteger()
        val retryRequests = CountDownLatch(10)
        var persistedCatalog: String? = null
        TestUpstream(modelsBodyProvider = {
            val requestNumber = modelRequests.incrementAndGet()
            if (requestNumber >= 3) retryRequests.countDown()
            if (requestNumber == 1) fullCatalog else reducedCatalog
        }).use { upstream ->
            val firstProxy = newProxy(
                upstream.baseUri,
                persistentModelCacheProvider = { persistedCatalog },
                persistentModelCacheSaver = { persistedCatalog = it },
            )
            try {
                firstProxy.start()
                assertTrue("gh-gpt-5.4-mini" in modelInfoIds(get(firstProxy.port, "/v1/model/info")))
            } finally {
                firstProxy.stop()
            }

            val restartedProxy = newProxy(
                upstream.baseUri,
                persistentModelCacheProvider = { persistedCatalog },
                persistentModelCacheSaver = { persistedCatalog = it },
                missingModelRetryDelays = List(10) { Duration.ZERO },
            )
            try {
                restartedProxy.start()

                assertTrue("gh-gpt-5.4-mini" in modelInfoIds(get(restartedProxy.port, "/v1/model/info")))
                assertTrue(retryRequests.await(2, TimeUnit.SECONDS), "background model retries did not complete")
                assertTrue(waitUntil { "gh-gpt-5.4-mini" !in modelInfoIds(get(restartedProxy.port, "/v1/model/info")) })
            } finally {
                restartedProxy.stop()
            }
        }
    }

    @Test
    fun stopsBackgroundRetriesWhenMissingModelReappears() {
        val fullCatalog = modelsBody(
            listOf(
                copilotModel("gpt-5-mini", family = "gpt-mini"),
                copilotModel("gpt-5.4-mini", family = "gpt-mini", endpoints = listOf("/responses")),
            ),
        )
        val reducedCatalog = modelsBody(listOf(copilotModel("gpt-5-mini", family = "gpt-mini")))
        val modelRequests = AtomicInteger()
        val firstRetry = CountDownLatch(1)
        var persistedCatalog: String? = null
        TestUpstream(modelsBodyProvider = {
            val requestNumber = modelRequests.incrementAndGet()
            if (requestNumber >= 3) firstRetry.countDown()
            when (requestNumber) {
                1 -> fullCatalog
                2 -> reducedCatalog
                else -> fullCatalog
            }
        }).use { upstream ->
            val firstProxy = newProxy(
                upstream.baseUri,
                persistentModelCacheProvider = { persistedCatalog },
                persistentModelCacheSaver = { persistedCatalog = it },
            )
            try {
                firstProxy.start()
                assertTrue("gh-gpt-5.4-mini" in modelInfoIds(get(firstProxy.port, "/v1/model/info")))
            } finally {
                firstProxy.stop()
            }

            val restartedProxy = newProxy(
                upstream.baseUri,
                persistentModelCacheProvider = { persistedCatalog },
                persistentModelCacheSaver = { persistedCatalog = it },
                missingModelRetryDelays = List(10) { Duration.ZERO },
            )
            try {
                restartedProxy.start()

                assertTrue("gh-gpt-5.4-mini" in modelInfoIds(get(restartedProxy.port, "/v1/model/info")))
                assertTrue(firstRetry.await(2, TimeUnit.SECONDS), "background model retry did not run")
                Thread.sleep(100)
                assertEquals(3, modelRequests.get())
            } finally {
                restartedProxy.stop()
            }
        }
    }

    @Test
    fun keepsMissingModelsWhenBackgroundRetriesCannotConfirmCatalog() {
        val fullCatalog = modelsBody(
            listOf(
                copilotModel("gpt-5-mini", family = "gpt-mini"),
                copilotModel("gpt-5.4-mini", family = "gpt-mini", endpoints = listOf("/responses")),
            ),
        )
        val reducedCatalog = modelsBody(listOf(copilotModel("gpt-5-mini", family = "gpt-mini")))
        val emptyCatalog = modelsBody(emptyList())
        val modelRequests = AtomicInteger()
        val retryRequests = CountDownLatch(10)
        var persistedCatalog: String? = null
        TestUpstream(modelsBodyProvider = {
            val requestNumber = modelRequests.incrementAndGet()
            if (requestNumber >= 3) retryRequests.countDown()
            when (requestNumber) {
                1 -> fullCatalog
                2 -> reducedCatalog
                else -> emptyCatalog
            }
        }).use { upstream ->
            val firstProxy = newProxy(
                upstream.baseUri,
                persistentModelCacheProvider = { persistedCatalog },
                persistentModelCacheSaver = { persistedCatalog = it },
            )
            try {
                firstProxy.start()
                assertTrue("gh-gpt-5.4-mini" in modelInfoIds(get(firstProxy.port, "/v1/model/info")))
            } finally {
                firstProxy.stop()
            }

            val restartedProxy = newProxy(
                upstream.baseUri,
                persistentModelCacheProvider = { persistedCatalog },
                persistentModelCacheSaver = { persistedCatalog = it },
                missingModelRetryDelays = List(10) { Duration.ZERO },
            )
            try {
                restartedProxy.start()

                assertTrue("gh-gpt-5.4-mini" in modelInfoIds(get(restartedProxy.port, "/v1/model/info")))
                assertTrue(retryRequests.await(2, TimeUnit.SECONDS), "background model retries did not complete")
                assertTrue("gh-gpt-5.4-mini" in modelInfoIds(get(restartedProxy.port, "/v1/model/info")))
            } finally {
                restartedProxy.stop()
            }
        }
    }

    private fun newProxy(
        upstreamBaseUri: URI,
        persistentModelCacheProvider: () -> String? = { null },
        persistentModelCacheSaver: (String?) -> Unit = {},
        missingModelRetryDelays: List<Duration> = emptyList(),
        modelCacheTtl: kotlin.time.Duration = 5.minutes,
    ): TestProxy {
        val port = freePort()
        val provider = GitHubCopilotSubscriptionProxyProvider(
            accessTokenProvider = { "github-token" },
            upstreamBaseUri = upstreamBaseUri,
            persistentModelCacheProvider = persistentModelCacheProvider,
            persistentModelCacheSaver = persistentModelCacheSaver,
            missingModelRetryDelays = missingModelRetryDelays,
            modelCacheTtl = modelCacheTtl,
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

    private fun modelInfoIds(response: HttpResponse<String>): List<String> {
        return JsonHelper.JSON.parseToJsonElement(response.body()).jsonObject["data"]!!.jsonArray
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }
    }

    private fun waitUntil(timeoutMillis: Long = 2_000, predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (predicate()) return true
            Thread.sleep(10)
        }
        return predicate()
    }

    private fun expectedChatUpstreamPath(modelId: String, endpoints: List<String>): String {
        val upstreamModelId = modelId.removePrefix("gh-")
        return when {
            "/v1/messages" in endpoints -> "/v1/messages"
            "/v1/responses" in endpoints && shouldBridgeChatToResponses(upstreamModelId) -> "/responses"
            else -> "/chat/completions"
        }
    }

    private fun shouldBridgeChatToResponses(upstreamModelId: String): Boolean {
        if (upstreamModelId.startsWith("mai-code-")) {
            return true
        }
        val gptMajor = GPT_MAJOR_REGEX.find(upstreamModelId)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return false
        return gptMajor >= 5 && !upstreamModelId.startsWith("gpt-5-mini")
    }

    private fun nextInferenceRequest(upstream: TestUpstream, modelId: String): CapturedRequest {
        repeat(5) {
            val next = upstream.requests.poll(2, TimeUnit.SECONDS)
            if (next != null && next.path != "/models") {
                return next
            }
        }
        error("Timed out waiting for inference request for $modelId")
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

    private class TestUpstream(
        private val modelsBodyProvider: () -> String = { modelsBody(copilotModels) },
    ) : AutoCloseable {
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
                if (exchange.requestURI.rawPath == "/image.png") {
                    val response = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
                    exchange.responseHeaders.set("Content-Type", "image/png")
                    exchange.sendResponseHeaders(200, response.size.toLong())
                    exchange.responseBody.use { output -> output.write(response) }
                    return@createContext
                }
                val responseBody = if (exchange.requestURI.rawPath == "/models") {
                    modelsBodyProvider()
                } else if (exchange.requestURI.rawPath == "/responses" && body.contains("\"stream\":true")) {
                    responsesStreamBody()
                } else if (exchange.requestURI.rawPath == "/v1/messages" && body.contains("\"tools\"") && body.contains("\"stream\":true")) {
                    anthropicToolUseStreamBody()
                } else if (exchange.requestURI.rawPath == "/v1/messages" && body.contains("\"tools\"")) {
                    anthropicToolUseBody()
                } else if (exchange.requestURI.rawPath == "/v1/messages" && body.contains("\"stream\":true")) {
                    anthropicStreamBody()
                } else if (exchange.requestURI.rawPath == "/v1/messages") {
                    anthropicMessageBody()
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
        private val GPT_MAJOR_REGEX = Regex("^gpt-(\\d+)")

        // Local fixture only: production discovers models from GitHub Copilot's /models endpoint.
        private val copilotModels = listOf(
            copilotModel("claude-haiku-4.5", family = "claude-haiku", endpoints = listOf("/v1/messages")),
            copilotModel("claude-sonnet-4.5", family = "claude-sonnet", endpoints = listOf("/v1/messages")),
            copilotModel(
                "claude-sonnet-4.6",
                family = "claude-sonnet",
                endpoints = listOf("/chat/completions", "/v1/messages")
            ),
            copilotModel("gemini-2.5-pro", family = "gemini-pro"),
            copilotModel("gemini-3-flash-preview", family = "gemini-flash"),
            copilotModel("gemini-3.1-pro-preview", family = "gemini-pro"),
            copilotModel("gemini-3.5-flash", family = "gemini-flash"),
            copilotModel("gpt-5-mini", family = "gpt-mini"),
            copilotModel("gpt-5.3-codex", family = "gpt-codex", endpoints = listOf("/responses")),
            copilotModel("gpt-5.4", family = "gpt", endpoints = listOf("/responses")),
            copilotModel("gpt-5.4-mini", family = "gpt-mini", endpoints = listOf("/responses")),
            copilotModel(
                "mai-code-1-flash-picker",
                family = "mai",
                endpoints = listOf("/responses"),
                toolCalls = null,
                maxOutputTokens = null,
            ),
            copilotModel("gpt-5.5", family = "gpt", pickerEnabled = false),
            copilotModel("disabled-model", family = "test", disabled = true),
        )

        private fun modelsBody(models: List<String>): String = "{\"data\":[" + models.joinToString(",") + "]}"

        private fun responsesStreamBody(): String {
            return "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hi from responses\"}\n\n" +
                    "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"hi from responses\"}]}],\"usage\":{\"input_tokens\":1,\"output_tokens\":2,\"total_tokens\":3}}}\n\n" +
                    "data: [DONE]\n\n"
        }

        private fun anthropicMessageBody(): String {
            return "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"hi from claude\"}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":1,\"output_tokens\":2}}"
        }

        private fun anthropicToolUseBody(): String {
            return "{\"id\":\"msg_2\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"get_weather\",\"input\":{\"city\":\"Berlin\"}}],\"stop_reason\":\"tool_use\",\"usage\":{\"input_tokens\":1,\"output_tokens\":2}}"
        }

        private fun anthropicToolUseStreamBody(): String {
            return "event: message_start\n" +
                    "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_2\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[]}}\n\n" +
                    "event: content_block_start\n" +
                    "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n" +
                    "event: content_block_delta\n" +
                    "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Sure.\"}}\n\n" +
                    "event: content_block_stop\n" +
                    "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n" +
                    "event: content_block_start\n" +
                    "data: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"get_weather\",\"input\":{}}}\n\n" +
                    "event: content_block_delta\n" +
                    "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"city\\\":\"}}\n\n" +
                    "event: content_block_delta\n" +
                    "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"Berlin\\\"}\"}}\n\n" +
                    "event: content_block_stop\n" +
                    "data: {\"type\":\"content_block_stop\",\"index\":1}\n\n" +
                    "event: message_delta\n" +
                    "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}}\n\n" +
                    "event: message_stop\n" +
                    "data: {\"type\":\"message_stop\"}\n\n" +
                    "data: [DONE]\n\n"
        }

        private fun anthropicStreamBody(): String {
            return "event: message_start\n" +
                    "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[]}}\n\n" +
                    "event: content_block_delta\n" +
                    "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"hi from claude\"}}\n\n" +
                    "event: message_delta\n" +
                    "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"input_tokens\":3,\"output_tokens\":5}}\n\n" +
                    "event: message_stop\n" +
                    "data: {\"type\":\"message_stop\"}\n\n" +
                    "data: [DONE]\n\n"
        }

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
