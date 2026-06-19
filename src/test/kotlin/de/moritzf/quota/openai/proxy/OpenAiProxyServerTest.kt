package de.moritzf.quota.openai.proxy

import com.sun.net.httpserver.HttpServer
import de.moritzf.quota.shared.JsonSupport
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OpenAiProxyServerTest {
    @Test
    fun rejectsInvalidLocalApiKeyBeforeCallingUpstream() {
        TestUpstream(responseBody = COMPLETED_RESPONSE_STREAM_WITH_TEXT).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/chat/completions"))
                        .header("Authorization", "Bearer wrong-local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(401, response.statusCode())
                val error = parseObject(response.body())["error"]!!.jsonObject
                assertEquals("auth_error", error["type"]?.jsonPrimitive?.content)
                assertEquals("Invalid or missing API key.", error["message"]?.jsonPrimitive?.content)
                assertNull(upstream.requests.poll(500, TimeUnit.MILLISECONDS))
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun forwardsUnknownModelAndSurfacesUpstreamRejection() {
        TestUpstream(
            responseStatus = 400,
            responseContentType = "application/json",
            responseBody = "{\"error\":{\"message\":\"Unknown model: future-model\",\"type\":\"invalid_request_error\",\"param\":\"model\",\"code\":\"model_not_found\"}}",
        ).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/chat/completions"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"future-model\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(400, response.statusCode())
                val error = parseObject(response.body())["error"]!!.jsonObject
                assertEquals("invalid_request_error", error["type"]?.jsonPrimitive?.content)
                assertEquals("Unknown model: future-model", error["message"]?.jsonPrimitive?.content)
                val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                val upstreamBody = parseObject(request.body)
                assertEquals("future-model", upstreamBody["model"]?.jsonPrimitive?.content)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun rejectsMalformedChatCompletionJsonBeforeCallingUpstream() {
        TestUpstream(responseBody = COMPLETED_RESPONSE_STREAM_WITH_TEXT).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/chat/completions"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-5.5\",\"messages\":"))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(400, response.statusCode())
                val error = parseObject(response.body())["error"]!!.jsonObject
                assertEquals("invalid_request_error", error["type"]?.jsonPrimitive?.content)
                assertEquals("Malformed JSON request body.", error["message"]?.jsonPrimitive?.content)
                assertNull(upstream.requests.poll(500, TimeUnit.MILLISECONDS))
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun wrapsGenericUpstreamFailureAsOpenAiError() {
        TestUpstream(
            responseStatus = 502,
            responseContentType = "application/json",
            responseBody = "{\"detail\":\"upstream gateway exploded\"}",
        ).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/responses"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-5.5\",\"input\":[]}"))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(502, response.statusCode())
                val error = parseObject(response.body())["error"]!!.jsonObject
                assertEquals("upstream_error", error["type"]?.jsonPrimitive?.content)
                assertEquals("upstream gateway exploded", error["message"]?.jsonPrimitive?.content)
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun proxiesChatCompletionsThroughAIProxyOauthWithQuotaAuthentication() {
        TestUpstream(responseBody = COMPLETED_RESPONSE_STREAM_WITH_TEXT).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/chat/completions"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"messages\":[" +
                                    "{\"role\":\"system\",\"content\":\"Follow protocol.\"}," +
                                    "{\"role\":\"user\",\"content\":\"Say pong.\"}]}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                val root = parseObject(response.body())
                assertEquals("chat.completion", root["object"]?.jsonPrimitive?.content)
                val message = root["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject
                assertEquals("assistant", message["role"]?.jsonPrimitive?.content)
                assertEquals("pong", message["content"]?.jsonPrimitive?.content)

                val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("POST", request.method)
                assertEquals("/backend-api/codex/responses", request.path)
                assertEquals("Bearer codex-token", request.firstHeader("Authorization"))
                assertEquals("account-1", request.firstHeader("chatgpt-account-id"))
                assertEquals(TEST_CODEX_VERSION, request.firstHeader("version"))
                assertNull(request.firstHeader("OpenAI-Beta"))

                val upstreamBody = parseObject(request.body)
                assertEquals("gpt-5.5", upstreamBody["model"]?.jsonPrimitive?.content)
                assertEquals("Follow protocol.", upstreamBody["instructions"]?.jsonPrimitive?.content)
                assertEquals(false, upstreamBody["store"]?.jsonPrimitive?.boolean)
                assertEquals(true, upstreamBody["stream"]?.jsonPrimitive?.boolean)
                assertEquals("message", upstreamBody["input"]!!.jsonArray[0].jsonObject["type"]?.jsonPrimitive?.content)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun convertsChatCompletionImagePartsToResponsesImageInput() {
        TestUpstream(responseBody = COMPLETED_RESPONSE_STREAM_WITH_TEXT).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/chat/completions"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"messages\":[" +
                                    "{\"role\":\"user\",\"content\":[" +
                                    "{\"type\":\"text\",\"text\":\"What is in this image?\"}," +
                                    "{\"type\":\"image_url\",\"image_url\":{" +
                                    "\"url\":\"$TEST_IMAGE_DATA_URL\",\"detail\":\"high\"}}]}]}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                val upstreamBody = parseObject(request.body)
                val content = upstreamBody["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
                assertEquals("input_text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
                val imagePart = content[1].jsonObject
                assertEquals("input_image", imagePart["type"]!!.jsonPrimitive.content)
                assertEquals(TEST_IMAGE_DATA_URL, imagePart["image_url"]!!.jsonPrimitive.content)
                assertEquals("high", imagePart["detail"]!!.jsonPrimitive.content)
                assertFalse("url" in imagePart)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun proxiesImageGenerationThroughCodexImageEndpoint() {
        TestUpstream(
            responseContentType = "application/json",
            responseBody = "{\"created\":1,\"data\":[{\"b64_json\":\"cG5n\"}],\"quality\":\"high\"}",
        ).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/images/generations"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-image-2\",\"prompt\":\"paint a blue whale\"," +
                                    "\"size\":\"1024x1024\",\"quality\":\"high\"}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                assertEquals(
                    "cG5n",
                    parseObject(response.body())["data"]!!.jsonArray[0].jsonObject["b64_json"]!!.jsonPrimitive.content,
                )
                val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("POST", request.method)
                assertEquals("/backend-api/codex/images/generations", request.path)
                assertEquals("Bearer codex-token", request.firstHeader("Authorization"))
                assertEquals("account-1", request.firstHeader("chatgpt-account-id"))
                val upstreamBody = parseObject(request.body)
                assertEquals("gpt-image-2", upstreamBody["model"]!!.jsonPrimitive.content)
                assertEquals("paint a blue whale", upstreamBody["prompt"]!!.jsonPrimitive.content)
                assertEquals("high", upstreamBody["quality"]!!.jsonPrimitive.content)
                assertFalse("store" in upstreamBody)
                assertFalse("stream" in upstreamBody)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun proxiesImageEditsThroughCodexImageEndpointWithoutV1Prefix() {
        TestUpstream(
            responseContentType = "application/json",
            responseBody = "{\"created\":1,\"data\":[{\"b64_json\":\"cG5n\"}]}",
        ).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/images/edits"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-image-2\",\"prompt\":\"add a red hat\"," +
                                    "\"images\":[{\"image_url\":\"$TEST_IMAGE_DATA_URL\"}]}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("/backend-api/codex/images/edits", request.path)
                val upstreamBody = parseObject(request.body)
                assertEquals("add a red hat", upstreamBody["prompt"]!!.jsonPrimitive.content)
                assertEquals(
                    TEST_IMAGE_DATA_URL,
                    upstreamBody["images"]!!.jsonArray[0].jsonObject["image_url"]!!.jsonPrimitive.content,
                )
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun mapsImageUpstreamQuotaErrorsToOpenAiEnvelope() {
        TestUpstream(
            responseStatus = 404,
            responseContentType = "application/json",
            responseBody = "{\"detail\":\"usage_limit_reached\"}",
        ).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/images/generations"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-image-2\",\"prompt\":\"paint a blue whale\"}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(429, response.statusCode())
                val error = parseObject(response.body())["error"]!!.jsonObject
                assertEquals("insufficient_quota", error["type"]!!.jsonPrimitive.content)
                assertEquals("insufficient_quota", error["code"]!!.jsonPrimitive.content)
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun proxiesAlphaSearchThroughCodexSearchEndpoint() {
        TestUpstream(
            responseContentType = "application/json",
            responseBody = "{\"encrypted_output\":\"ciphertext\",\"output\":\"search result\"}",
        ).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/alpha/search"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"id\":\"search-session\",\"model\":\"gpt-5.5\"," +
                                    "\"commands\":{\"search_query\":[{\"q\":\"OpenAI news\"}]}}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                assertEquals("search result", parseObject(response.body())["output"]!!.jsonPrimitive.content)
                val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("POST", request.method)
                assertEquals("/backend-api/codex/alpha/search", request.path)
                assertEquals("Bearer codex-token", request.firstHeader("Authorization"))
                assertEquals("account-1", request.firstHeader("chatgpt-account-id"))
                val upstreamBody = parseObject(request.body)
                assertEquals("search-session", upstreamBody["id"]!!.jsonPrimitive.content)
                assertEquals("gpt-5.5", upstreamBody["model"]!!.jsonPrimitive.content)
                assertEquals(
                    "OpenAI news",
                    upstreamBody["commands"]!!.jsonObject["search_query"]!!.jsonArray[0].jsonObject["q"]!!
                        .jsonPrimitive.content,
                )
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun proxiesResponsesCompactThroughCodexCompactEndpoint() {
        TestUpstream(
            responseContentType = "application/json",
            responseHeaders = mapOf("x-codex-turn-state" to "turn-state-1"),
            responseBody = "{\"output\":[{\"type\":\"message\",\"role\":\"assistant\"," +
                "\"content\":[{\"type\":\"output_text\",\"text\":\"summary\"}]}]}",
        ).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/responses/compact"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"input\":[{\"type\":\"message\"," +
                                    "\"role\":\"user\",\"content\":[{\"type\":\"input_text\"," +
                                    "\"text\":\"summarize\"}]}]}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                assertEquals(
                    "turn-state-1",
                    response.headers().firstValue("x-codex-turn-state").orElse(null),
                )
                assertEquals(
                    "summary",
                    parseObject(response.body())["output"]!!.jsonArray[0]
                        .jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
                )
                val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("POST", request.method)
                assertEquals("/backend-api/codex/responses/compact", request.path)
                assertEquals("Bearer codex-token", request.firstHeader("Authorization"))
                val upstreamBody = parseObject(request.body)
                assertEquals("gpt-5.5", upstreamBody["model"]!!.jsonPrimitive.content)
                assertEquals(
                    "summarize",
                    upstreamBody["input"]!!.jsonArray[0].jsonObject["content"]!!
                        .jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
                )
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun proxiesMemoryTraceSummarizeThroughCodexMemoriesEndpoint() {
        TestUpstream(
            responseContentType = "application/json",
            responseBody = "{\"output\":[{\"trace_summary\":\"trace summary\"," +
                "\"memory_summary\":\"memory summary\"}]}",
        ).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/memories/trace_summarize"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"traces\":[{\"id\":\"trace-1\"," +
                                    "\"metadata\":{\"source_path\":\"/tmp/trace.json\"}," +
                                    "\"items\":[{\"type\":\"message\",\"role\":\"user\"," +
                                    "\"content\":[{\"type\":\"input_text\",\"text\":\"remember this\"}]}]}]}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                assertEquals(
                    "memory summary",
                    parseObject(response.body())["output"]!!.jsonArray[0].jsonObject["memory_summary"]!!
                        .jsonPrimitive.content,
                )
                val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("POST", request.method)
                assertEquals("/backend-api/codex/memories/trace_summarize", request.path)
                assertEquals("Bearer codex-token", request.firstHeader("Authorization"))
                assertEquals("account-1", request.firstHeader("chatgpt-account-id"))
                val upstreamBody = parseObject(request.body)
                assertEquals("gpt-5.5", upstreamBody["model"]!!.jsonPrimitive.content)
                assertEquals("trace-1", upstreamBody["traces"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
                assertEquals(
                    "/tmp/trace.json",
                    upstreamBody["traces"]!!.jsonArray[0].jsonObject["metadata"]!!
                        .jsonObject["source_path"]!!.jsonPrimitive.content,
                )
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun sanitizesResponsesRequestsBeforeSendingToCodex() {
        TestUpstream(responseBody = COMPLETED_RESPONSE_STREAM_WITH_TEXT).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/responses"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"input\":[],\"store\":true," +
                                    "\"stream\":false,\"temperature\":1.0,\"max_output_tokens\":20}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                val upstreamBody = parseObject(request.body)
                assertEquals(false, upstreamBody["store"]?.jsonPrimitive?.boolean)
                assertEquals(true, upstreamBody["stream"]?.jsonPrimitive?.boolean)
                assertFalse("temperature" in upstreamBody)
                assertFalse("max_output_tokens" in upstreamBody)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun proxiesHostedWebSearchThroughResponsesEndpoint() {
        TestUpstream(responseBody = RESPONSE_STREAM_WITH_TEXT_DELTAS).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/responses"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"instructions\":\"Use web search.\"," +
                                    "\"stream\":true,\"input\":[{\"type\":\"message\",\"role\":\"user\"," +
                                    "\"content\":[{\"type\":\"input_text\",\"text\":\"Search OpenAI news\"}]}]," +
                                    "\"tools\":[{\"type\":\"web_search\",\"external_web_access\":true," +
                                    "\"search_context_size\":\"medium\"," +
                                    "\"search_content_types\":[\"text\"]}]}"
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                assertTrue(response.body().contains("response.output_text.delta"))
                val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("POST", request.method)
                assertEquals("/backend-api/codex/responses", request.path)
                assertEquals("Bearer codex-token", request.firstHeader("Authorization"))
                assertEquals("account-1", request.firstHeader("chatgpt-account-id"))
                assertEquals(TEST_CODEX_VERSION, request.firstHeader("version"))
                assertNull(request.firstHeader("OpenAI-Beta"))

                val upstreamBody = parseObject(request.body)
                assertEquals("gpt-5.5", upstreamBody["model"]!!.jsonPrimitive.content)
                assertEquals("Use web search.", upstreamBody["instructions"]!!.jsonPrimitive.content)
                assertEquals(false, upstreamBody["store"]!!.jsonPrimitive.boolean)
                assertEquals(true, upstreamBody["stream"]!!.jsonPrimitive.boolean)
                assertEquals(
                    "Search OpenAI news",
                    upstreamBody["input"]!!.jsonArray[0].jsonObject["content"]!!
                        .jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
                )
                val tool = upstreamBody["tools"]!!.jsonArray[0].jsonObject
                assertEquals("web_search", tool["type"]!!.jsonPrimitive.content)
                assertEquals(true, tool["external_web_access"]!!.jsonPrimitive.boolean)
                assertEquals("medium", tool["search_context_size"]!!.jsonPrimitive.content)
                assertEquals("text", tool["search_content_types"]!!.jsonArray[0].jsonPrimitive.content)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun proxiesHostedImageGenerationThroughResponsesEndpoint() {
        TestUpstream(responseBody = HOSTED_IMAGE_GENERATION_RESPONSE_STREAM).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/responses"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"instructions\":\"Generate images.\"," +
                                    "\"stream\":true,\"input\":[{\"type\":\"message\",\"role\":\"user\"," +
                                    "\"content\":[{\"type\":\"input_text\",\"text\":\"Draw a cat\"}]}]," +
                                    "\"tools\":[{\"type\":\"image_generation\",\"output_format\":\"png\"}]}"
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                assertTrue(response.body().contains("image_generation_call"))
                val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("POST", request.method)
                assertEquals("/backend-api/codex/responses", request.path)
                assertEquals("Bearer codex-token", request.firstHeader("Authorization"))
                assertEquals("account-1", request.firstHeader("chatgpt-account-id"))
                assertEquals(TEST_CODEX_VERSION, request.firstHeader("version"))
                assertNull(request.firstHeader("OpenAI-Beta"))

                val upstreamBody = parseObject(request.body)
                assertEquals("gpt-5.5", upstreamBody["model"]!!.jsonPrimitive.content)
                assertEquals("Generate images.", upstreamBody["instructions"]!!.jsonPrimitive.content)
                assertEquals(false, upstreamBody["store"]!!.jsonPrimitive.boolean)
                assertEquals(true, upstreamBody["stream"]!!.jsonPrimitive.boolean)
                assertEquals(
                    "Draw a cat",
                    upstreamBody["input"]!!.jsonArray[0].jsonObject["content"]!!
                        .jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
                )
                val tool = upstreamBody["tools"]!!.jsonArray[0].jsonObject
                assertEquals("image_generation", tool["type"]!!.jsonPrimitive.content)
                assertEquals("png", tool["output_format"]!!.jsonPrimitive.content)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun convertsPlainJunieResponsesTextToSubmitToolCall() {
        TestUpstream(responseBody = COMPLETED_RESPONSE_STREAM_WITH_TEXT).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/responses"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"instructions\":" +
                                    "\"You are Junie, an autonomous programmer developed by JetBrains. " +
                                    "You're working with a special interface.\",\"input\":[]," +
                                    "\"tools\":[{\"type\":\"function\",\"name\":\"submit\"," +
                                    "\"parameters\":{\"type\":\"object\",\"properties\":{}}}]}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                val outputItem = parseObject(response.body())["output"]!!.jsonArray[0].jsonObject
                assertEquals("function_call", outputItem["type"]!!.jsonPrimitive.content)
                assertEquals("submit", outputItem["name"]!!.jsonPrimitive.content)
                val arguments = parseObject(outputItem["arguments"]!!.jsonPrimitive.content)
                assertEquals("pong", arguments["solution_summary"]!!.jsonPrimitive.content)
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun convertsPlainJunieChatTextToSubmitCommandForLegacyFunctions() {
        TestUpstream(responseBody = COMPLETED_RESPONSE_STREAM_WITH_TEXT).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/chat/completions"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"messages\":[" +
                                    "{\"role\":\"system\",\"content\":\"You are Junie and must use submit.\"}," +
                                    "{\"role\":\"user\",\"content\":\"Reply with exactly: pong\"}]," +
                                    "\"functions\":[{\"name\":\"submit\",\"description\":\"Submit final answer\"," +
                                    "\"parameters\":{\"type\":\"object\",\"properties\":{}}}]," +
                                    "\"function_call\":{\"name\":\"submit\"}}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                val message = parseObject(response.body())["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject
                val choice = parseObject(response.body())["choices"]!!.jsonArray[0].jsonObject
                assertEquals("stop", choice["finish_reason"]!!.jsonPrimitive.content)
                assertEquals("<THOUGHT>pong</THOUGHT>\n<COMMAND>submit</COMMAND>", message["content"]!!.jsonPrimitive.content)
                assertFalse("function_call" in message)

                val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                val upstreamBody = parseObject(request.body)
                assertEquals("submit", upstreamBody["tools"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
                assertEquals("submit", upstreamBody["tool_choice"]!!.jsonObject["name"]!!.jsonPrimitive.content)
                assertFalse("functions" in upstreamBody)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun convertsPlainJunieChatTextToAnswerToolCall() {
        TestUpstream(responseBody = COMPLETED_RESPONSE_STREAM_WITH_TEXT).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/chat/completions"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"messages\":[" +
                                    "{\"role\":\"system\",\"content\":\"You are Junie. Answer via answer tool.\"}," +
                                    "{\"role\":\"user\",\"content\":\"test\"}]," +
                                    "\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"answer\"," +
                                    "\"parameters\":{\"type\":\"object\",\"properties\":{}}}}]}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                val choice = parseObject(response.body())["choices"]!!.jsonArray[0].jsonObject
                assertEquals("tool_calls", choice["finish_reason"]!!.jsonPrimitive.content)
                val message = choice["message"]!!.jsonObject
                assertEquals("pong", message["content"]!!.jsonPrimitive.content)
                val toolCall = message["tool_calls"]!!.jsonArray[0].jsonObject
                assertEquals("answer", toolCall["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
                val arguments = parseObject(toolCall["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)
                assertEquals("pong", arguments["full_answer"]!!.jsonPrimitive.content)

                val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                val upstreamBody = parseObject(request.body)
                assertEquals("answer", upstreamBody["tools"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun passesThroughNativeJunieToolCallsForChatRequestsWithTools() {
        TestUpstream(responseBody = COMPLETED_RESPONSE_STREAM_WITH_FUNCTION_CALL).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/chat/completions"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"messages\":[" +
                                    "{\"role\":\"system\",\"content\":\"You are Junie. " +
                                    "You're working with a special interface.\"}," +
                                    "{\"role\":\"user\",\"content\":\"Create issue.md\"}]," +
                                    "\"tools\":[" +
                                    "{\"type\":\"function\",\"function\":{\"name\":\"create\"," +
                                    "\"parameters\":{\"type\":\"object\",\"properties\":{}}}}," +
                                    "{\"type\":\"function\",\"function\":{\"name\":\"submit\"," +
                                    "\"parameters\":{\"type\":\"object\",\"properties\":{}}}}]}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                val choice = parseObject(response.body())["choices"]!!.jsonArray[0].jsonObject
                assertEquals("tool_calls", choice["finish_reason"]!!.jsonPrimitive.content)
                val message = choice["message"]!!.jsonObject
                val toolCall = message["tool_calls"]!!.jsonArray[0].jsonObject
                assertEquals("call_1", toolCall["id"]!!.jsonPrimitive.content)
                assertEquals("create", toolCall["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
                assertEquals(
                    "{\"path\":\"issue.md\"}",
                    toolCall["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content,
                )
                assertFalse((message["content"]?.jsonPrimitive?.contentOrNull ?: "").contains("<COMMAND>"))

                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun reformatsJunieUpdateMarkupForNativeToolChatResponses() {
        TestUpstream(responseBody = COMPLETED_RESPONSE_STREAM_WITH_UPDATE_TEXT_AND_FUNCTION_CALL).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/chat/completions"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"messages\":[" +
                                    "{\"role\":\"system\",\"content\":\"You are Junie. " +
                                    "You're working with a special interface.\"}," +
                                    "{\"role\":\"user\",\"content\":\"Install the SDK\"}]," +
                                    "\"tools\":[" +
                                    "{\"type\":\"function\",\"function\":{\"name\":\"bash\"," +
                                    "\"parameters\":{\"type\":\"object\",\"properties\":{}}}}," +
                                    "{\"type\":\"function\",\"function\":{\"name\":\"submit\"," +
                                    "\"parameters\":{\"type\":\"object\",\"properties\":{}}}}]}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                val choice = parseObject(response.body())["choices"]!!.jsonArray[0].jsonObject
                assertEquals("tool_calls", choice["finish_reason"]!!.jsonPrimitive.content)
                val message = choice["message"]!!.jsonObject
                val content = message["content"]!!.jsonPrimitive.content
                assertFalse(content.contains("<UPDATE>"))
                assertFalse(content.contains("<PLAN>"))
                assertFalse(content.contains("<PREVIOUS_STEP>"))
                assertTrue(content.contains("Checked the SDK state."))
                assertTrue(content.contains("Plan:\n1. Check current state ✓"))
                assertTrue(content.contains("Next: Install the latest SDK."))
                val toolCall = message["tool_calls"]!!.jsonArray[0].jsonObject
                assertEquals("bash", toolCall["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)

                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun reformatsJunieUpdateMarkupForNativeToolStreamingChatResponses() {
        TestUpstream(responseBody = JUNIE_UPDATE_STREAM_WITH_FUNCTION_CALL).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/chat/completions"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"stream\":true,\"messages\":[" +
                                    "{\"role\":\"system\",\"content\":\"You are Junie. " +
                                    "You're working with a special interface.\"}," +
                                    "{\"role\":\"user\",\"content\":\"Install the SDK\"}]," +
                                    "\"tools\":[" +
                                    "{\"type\":\"function\",\"function\":{\"name\":\"bash\"," +
                                    "\"parameters\":{\"type\":\"object\",\"properties\":{}}}}," +
                                    "{\"type\":\"function\",\"function\":{\"name\":\"submit\"," +
                                    "\"parameters\":{\"type\":\"object\",\"properties\":{}}}}]}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                val body = response.body()
                assertFalse(body.contains("<UPDATE>"))
                assertFalse(body.contains("<PLAN>"))
                assertTrue(body.contains("Checked the SDK state."))
                assertTrue(body.contains("Next: Install the latest SDK."))
                assertTrue(body.contains("\"name\":\"bash\""))
                assertTrue(body.contains("\"finish_reason\":\"tool_calls\""))

                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun reformatsJunieUpdateMarkupForNativeToolResponsesRequests() {
        TestUpstream(responseBody = COMPLETED_RESPONSE_STREAM_WITH_UPDATE_TEXT_AND_FUNCTION_CALL).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/responses"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"instructions\":" +
                                    "\"You are Junie, an autonomous programmer developed by JetBrains.\"," +
                                    "\"input\":[],\"tools\":[" +
                                    "{\"type\":\"function\",\"name\":\"bash\"," +
                                    "\"parameters\":{\"type\":\"object\",\"properties\":{}}}," +
                                    "{\"type\":\"function\",\"name\":\"submit\"," +
                                    "\"parameters\":{\"type\":\"object\",\"properties\":{}}}]}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                val output = parseObject(response.body())["output"]!!.jsonArray
                val textPart = output[0].jsonObject["content"]!!.jsonArray[0].jsonObject
                val text = textPart["text"]!!.jsonPrimitive.content
                assertFalse(text.contains("<UPDATE>"))
                assertTrue(text.contains("Checked the SDK state."))
                assertTrue(text.contains("Next: Install the latest SDK."))
                assertEquals("function_call", output[1].jsonObject["type"]!!.jsonPrimitive.content)

                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun reconstructsFunctionCallFromOutputItemEventsWhenCompletedOutputIsEmpty() {
        TestUpstream(responseBody = FUNCTION_CALL_STREAM_WITH_EMPTY_COMPLETED_OUTPUT).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/chat/completions"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"messages\":[" +
                                    "{\"role\":\"user\",\"content\":\"Create issue.md\"}]," +
                                    "\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"create\"," +
                                    "\"parameters\":{\"type\":\"object\",\"properties\":{}}}}]}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                val choice = parseObject(response.body())["choices"]!!.jsonArray[0].jsonObject
                assertEquals("tool_calls", choice["finish_reason"]!!.jsonPrimitive.content)
                val toolCall = choice["message"]!!.jsonObject["tool_calls"]!!.jsonArray[0].jsonObject
                assertEquals("call_1", toolCall["id"]!!.jsonPrimitive.content)
                assertEquals("create", toolCall["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
                assertEquals(
                    "{\"path\":\"issue.md\"}",
                    toolCall["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content,
                )

                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun streamsFunctionCallArgumentsKeyedByItemId() {
        TestUpstream(responseBody = FUNCTION_CALL_STREAM_WITH_EMPTY_COMPLETED_OUTPUT).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/chat/completions"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"stream\":true,\"messages\":[" +
                                    "{\"role\":\"user\",\"content\":\"Create issue.md\"}]," +
                                    "\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"create\"," +
                                    "\"parameters\":{\"type\":\"object\",\"properties\":{}}}}]}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                val arguments = response.body().lineSequence()
                    .filter { it.startsWith("data: ") && it != "data: [DONE]" }
                    .map { parseObject(it.removePrefix("data: ")) }
                    .flatMap { chunk -> chunk["choices"]!!.jsonArray.asSequence() }
                    .mapNotNull { choice ->
                        choice.jsonObject["delta"]?.jsonObject?.get("tool_calls")?.jsonArray
                            ?.firstOrNull()?.jsonObject?.get("function")?.jsonObject
                            ?.get("arguments")?.jsonPrimitive?.contentOrNull
                    }
                    .joinToString("")
                assertEquals("{\"path\":\"issue.md\"}", arguments)
                assertTrue(response.body().contains("\"finish_reason\":\"tool_calls\""))
                assertTrue(response.body().contains("\"name\":\"create\""))

                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun synthesizesSubmitToolCallForStreamingJunieChatWithToolsAndTextOnlyResponse() {
        TestUpstream(responseBody = RESPONSE_STREAM_WITH_TEXT_DELTAS).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/chat/completions"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"stream\":true,\"messages\":[" +
                                    "{\"role\":\"system\",\"content\":\"You are Junie. " +
                                    "You're working with a special interface.\"}," +
                                    "{\"role\":\"user\",\"content\":\"test\"}]," +
                                    "\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"submit\"," +
                                    "\"parameters\":{\"type\":\"object\",\"properties\":{}}}}]}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                assertTrue(response.body().contains("\"tool_calls\""))
                assertTrue(response.body().contains("\"name\":\"submit\""))
                assertTrue(response.body().contains("solution_summary"))
                assertTrue(response.body().contains("\"finish_reason\":\"tool_calls\""))
                assertFalse(response.body().contains("<COMMAND>"))

                val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                val upstreamBody = parseObject(request.body)
                assertEquals("submit", upstreamBody["tools"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun convertsPlainJunieChatTextToSubmitCommandWithoutToolMetadata() {
        TestUpstream(responseBody = COMPLETED_RESPONSE_STREAM_WITH_TEXT).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/chat/completions"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"messages\":[" +
                                    "{\"role\":\"system\",\"content\":\"You are Junie. " +
                                    "Other Modes - answer using tool calls only. Report via the submit tool.\"}," +
                                    "{\"role\":\"user\",\"content\":\"test\"}]}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                val choice = parseObject(response.body())["choices"]!!.jsonArray[0].jsonObject
                assertEquals("stop", choice["finish_reason"]!!.jsonPrimitive.content)
                val message = choice["message"]!!.jsonObject
                assertEquals("<THOUGHT>pong</THOUGHT>\n<COMMAND>submit</COMMAND>", message["content"]!!.jsonPrimitive.content)
                assertFalse("tool_calls" in message)

                val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                val upstreamBody = parseObject(request.body)
                assertFalse("tools" in upstreamBody)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun passesThroughPlainTextForJunieUtilityPromptsDespiteCommandStopSequence() {
        // Junie attaches stop:["</COMMAND>"] to every LLM call, including plain-text
        // utility prompts like the task-name summarizer whose output it displays
        // verbatim (e.g. as the terminal tab title). Those must not be wrapped in
        // the THOUGHT/COMMAND protocol.
        TestUpstream(responseBody = COMPLETED_RESPONSE_STREAM_WITH_TEXT).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/chat/completions"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"stop\":[\"</COMMAND>\"],\"messages\":[" +
                                    "{\"role\":\"system\",\"content\":\"You are a programming task description summarizer\"}," +
                                    "{\"role\":\"user\",\"content\":\"Provide a short, helpful task name of 5-10 words. " +
                                    "Return ONLY the name, nothing else.\"}]}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                val choice = parseObject(response.body())["choices"]!!.jsonArray[0].jsonObject
                val message = choice["message"]!!.jsonObject
                assertEquals("pong", message["content"]!!.jsonPrimitive.content)
                assertFalse("tool_calls" in message)

                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun convertsPlainJunieStreamingChatTextToSubmitCommandWithoutToolMetadata() {
        TestUpstream(responseBody = RESPONSE_STREAM_WITH_TEXT_DELTAS).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/chat/completions"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"stream\":true,\"messages\":[" +
                                    "{\"role\":\"system\",\"content\":\"You are Junie. " +
                                    "Other Modes - answer using tool calls only. Report via the submit tool.\"}," +
                                    "{\"role\":\"user\",\"content\":\"test\"}]}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                assertTrue(response.body().contains("<THOUGHT>pong</THOUGHT>"))
                assertTrue(response.body().contains("<COMMAND>submit</COMMAND>"))
                assertFalse(response.body().contains("tool_calls"))
                assertFalse(response.body().contains("\"content\":\"po\""))

                val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                val upstreamBody = parseObject(request.body)
                assertEquals(true, upstreamBody["stream"]?.jsonPrimitive?.boolean)
                assertFalse("tools" in upstreamBody)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun stripsJunieProtocolTagsFromSyntheticStreamingCommandSummary() {
        TestUpstream(responseBody = COMPLETED_RESPONSE_STREAM_WITH_JUNIE_PROTOCOL_TEXT).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/chat/completions"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"stream\":true,\"messages\":[" +
                                    "{\"role\":\"system\",\"content\":\"You are Junie. " +
                                    "Other Modes - answer using tool calls only. Report via the submit tool.\"}," +
                                    "{\"role\":\"user\",\"content\":\"test\"}]}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                assertTrue(response.body().contains("<COMMAND>submit</COMMAND>"))
                assertTrue(response.body().contains("Finished proxy response formatting."))
                assertTrue(response.body().contains("Retry Junie in the IDE."))
                assertFalse(response.body().contains("tool_calls"))
                assertFalse(response.body().contains("<UPDATE>"))
                assertFalse(response.body().contains("<PREVIOUS_STEP>"))
                assertFalse(response.body().contains("Internal analysis should not be shown."))
                assertFalse(response.body().contains("<NEXT_STEP>"))

                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun convertsPlainJunieStreamingChatTextToCommandMarkerForXmlProtocol() {
        TestUpstream(responseBody = RESPONSE_STREAM_WITH_TEXT_DELTAS).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/chat/completions"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"stream\":true,\"messages\":[" +
                                    "{\"role\":\"system\",\"content\":\"You are Junie. Use the special interface.\"}," +
                                    "{\"role\":\"user\",\"content\":\"test\"}]}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                assertTrue(response.body().contains("<THOUGHT>pong</THOUGHT>"))
                assertTrue(response.body().contains("<COMMAND>submit</COMMAND>"))
                assertFalse(response.body().contains("tool_calls"))
                assertFalse(response.body().contains("\"content\":\"po\""))

                val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                val upstreamBody = parseObject(request.body)
                assertEquals(true, upstreamBody["stream"]?.jsonPrimitive?.boolean)
                assertFalse("tools" in upstreamBody)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun reasoningTierSuffixForwardsBaseModelAndEffortUpstream() {
        TestUpstream(responseBody = COMPLETED_RESPONSE_STREAM_WITH_TEXT).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/chat/completions"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5 (xhigh)\",\"messages\":[" +
                                    "{\"role\":\"user\",\"content\":\"hi\"}]}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                val upstreamBody = parseObject(request.body)
                assertEquals("gpt-5.5", upstreamBody["model"]!!.jsonPrimitive.content)
                assertEquals("xhigh", upstreamBody["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun reasoningTierSuffixWinsOverSeparateReasoningEffort() {
        TestUpstream(responseBody = COMPLETED_RESPONSE_STREAM_WITH_TEXT).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/chat/completions"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5 (high)\",\"reasoning_effort\":\"medium\"," +
                                    "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                val upstreamBody = parseObject(request.body)
                assertEquals("gpt-5.5", upstreamBody["model"]!!.jsonPrimitive.content)
                assertEquals("high", upstreamBody["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun returnsConfiguredModelsLocallyWithoutUpstreamRequest() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/models"))
                        .header("Authorization", "Bearer local-key")
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                val root = parseObject(response.body())
                val models = root["data"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
                // Each base model is advertised bare plus one entry per supported reasoning tier
                // so Junie can build its tier selector from the model names.
                assertEquals(
                    listOf(
                        "gpt-5.5", "gpt-5.5 (low)", "gpt-5.5 (medium)", "gpt-5.5 (high)", "gpt-5.5 (xhigh)",
                        "gpt-5.4", "gpt-5.4 (low)", "gpt-5.4 (medium)", "gpt-5.4 (high)", "gpt-5.4 (xhigh)",
                        "gpt-5.4-mini", "gpt-5.4-mini (medium)", "gpt-5.4-mini (high)",
                        "gpt-5.3-codex-spark", "gpt-5.3-codex-spark (low)", "gpt-5.3-codex-spark (medium)",
                        "gpt-5.3-codex-spark (high)", "gpt-5.3-codex-spark (xhigh)",
                    ),
                    models,
                )
                // gpt-5.5-pro is not advertised: the Codex backend rejects it for ChatGPT accounts.
                assertFalse(models.any { it.startsWith("gpt-5.5-pro") })
                assertNull(upstream.requests.poll(200, TimeUnit.MILLISECONDS))
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun returnsLiteLlmModelInfoLocallyWithoutUpstreamRequest() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/model/info"))
                        .header("Authorization", "Bearer local-key")
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                val root = parseObject(response.body())
                val firstModel = root["data"]!!.jsonArray[0].jsonObject
                assertEquals("gpt-5.5", firstModel["id"]!!.jsonPrimitive.content)
                assertEquals("gpt-5.5", firstModel["model_name"]!!.jsonPrimitive.content)
                assertEquals("gpt-5.5", firstModel["litellm_params"]!!.jsonObject["model"]!!.jsonPrimitive.content)
                assertEquals("chat", firstModel["model_info"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
                assertNull(upstream.requests.poll(200, TimeUnit.MILLISECONDS))
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun servesHealthReadinessWithoutAuthenticationOrUpstreamRequest() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val health = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/health"))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                val readiness = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/health/readiness"))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, health.statusCode())
                assertEquals(true, parseObject(health.body())["ok"]!!.jsonPrimitive.boolean)
                assertEquals(200, readiness.statusCode())
                assertEquals("healthy", parseObject(readiness.body())["status"]!!.jsonPrimitive.content)
                assertNull(upstream.requests.poll(200, TimeUnit.MILLISECONDS))
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun handlesCorsPreflightWithoutAuthenticationOrUpstreamRequest() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/chat/completions"))
                        .header("Origin", "https://client.example")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,x-smoke")
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(204, response.statusCode())
                assertEquals("*", response.headers().firstValue("Access-Control-Allow-Origin").orElse(null))
                assertEquals(
                    "GET,POST,OPTIONS",
                    response.headers().firstValue("Access-Control-Allow-Methods").orElse(null),
                )
                assertEquals(
                    "authorization,x-smoke",
                    response.headers().firstValue("Access-Control-Allow-Headers").orElse(null),
                )
                assertNull(upstream.requests.poll(200, TimeUnit.MILLISECONDS))
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun servesJsonNotFoundEnvelopeForUnknownGetRoute() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/not-a-route"))
                        .header("Authorization", "Bearer local-key")
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(404, response.statusCode())
                val error = parseObject(response.body())["error"]!!.jsonObject
                assertEquals("not_found_error", error["type"]!!.jsonPrimitive.content)
                assertEquals("Route not found.", error["message"]!!.jsonPrimitive.content)
                assertNull(upstream.requests.poll(200, TimeUnit.MILLISECONDS))
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun servesResponsesStreamWithSseHeaders() {
        TestUpstream(responseBody = RESPONSE_STREAM_WITH_TEXT_DELTAS).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/responses"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"stream\":true,\"input\":[]}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                assertTrue(
                    response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"),
                )
                assertEquals("no-cache, no-transform", response.headers().firstValue("Cache-Control").orElse(null))
                assertEquals("no", response.headers().firstValue("X-Accel-Buffering").orElse(null))
                assertTrue(response.body().contains("response.output_text.delta"))
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun reportsUsageForAuthenticatedLocalKey() {
        TestUpstream(responseBody = COMPLETED_RESPONSE_STREAM_WITH_TEXT).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val completion = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/chat/completions"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                val usage = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/usage"))
                        .header("Authorization", "Bearer local-key")
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, completion.statusCode())
                assertEquals(200, usage.statusCode())
                val root = parseObject(usage.body())
                val local = root["keys"]!!.jsonArray.single().jsonObject
                assertEquals("local", local["name"]!!.jsonPrimitive.content)
                assertEquals("1", local["prompt_tokens"]!!.jsonPrimitive.content)
                assertEquals("1", local["completion_tokens"]!!.jsonPrimitive.content)
                assertEquals("2", root["total"]!!.jsonObject["total_tokens"]!!.jsonPrimitive.content)
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertNull(upstream.requests.poll(200, TimeUnit.MILLISECONDS))
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun rejectsRequestsWithoutLocalApiKey() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/responses"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(401, response.statusCode())
                assertNull(upstream.requests.poll(200, TimeUnit.MILLISECONDS))
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun doesNotExpandPreviousResponseIdWhenReplayCacheDisabled() {
        TestUpstream(responseBody = COMPLETED_RESPONSE_STREAM_WITH_TEXT).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/responses"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"stream\":false,\"store\":false," +
                                    "\"previous_response_id\":\"resp_unknown\",\"input\":[" +
                                    "{\"type\":\"message\",\"role\":\"user\",\"content\":\"hi\"}]}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                val upstreamBody = parseObject(request.body)
                // The replay cache is off by default, so the reference is forwarded verbatim
                // rather than being expanded/removed by local replay bookkeeping.
                assertEquals("resp_unknown", upstreamBody["previous_response_id"]?.jsonPrimitive?.content)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun hoistsSystemInputMessagesIntoInstructionsForResponses() {
        TestUpstream(responseBody = COMPLETED_RESPONSE_STREAM_WITH_TEXT).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/responses"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"stream\":false,\"store\":false,\"input\":[" +
                                    "{\"type\":\"message\",\"role\":\"system\",\"content\":\"SYSTEM_PROMPT_TOKEN\"}," +
                                    "{\"type\":\"message\",\"role\":\"user\",\"content\":" +
                                    "[{\"type\":\"input_text\",\"text\":\"hi\"}]}]}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                val upstreamBody = parseObject(request.body)
                assertTrue(
                    upstreamBody["instructions"]!!.jsonPrimitive.content.contains("SYSTEM_PROMPT_TOKEN"),
                )
                val input = upstreamBody["input"]!!.jsonArray
                assertEquals(1, input.size)
                assertEquals("user", input[0].jsonObject["role"]!!.jsonPrimitive.content)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun preservesImageInputForJunieResponsesRequests() {
        TestUpstream(responseBody = COMPLETED_RESPONSE_STREAM_WITH_TEXT).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/responses"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"stream\":false,\"store\":false," +
                                    "\"input\":[" +
                                    "{\"type\":\"message\",\"role\":\"system\",\"content\":" +
                                    "\"You are Junie, an autonomous programmer developed by JetBrains.\"}," +
                                    "{\"type\":\"message\",\"role\":\"user\",\"content\":[" +
                                    "{\"type\":\"input_text\",\"text\":\"Describe the screenshot.\"}," +
                                    "{\"type\":\"input_image\",\"image_url\":\"$TEST_IMAGE_DATA_URL\"," +
                                    "\"detail\":\"original\"}]}]}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                val upstreamBody = parseObject(request.body)
                assertTrue(upstreamBody["instructions"]!!.jsonPrimitive.content.contains("You are Junie"))
                val input = upstreamBody["input"]!!.jsonArray
                assertEquals(1, input.size)
                val content = input[0].jsonObject["content"]!!.jsonArray
                assertEquals("input_text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
                val imagePart = content[1].jsonObject
                assertEquals("input_image", imagePart["type"]!!.jsonPrimitive.content)
                assertEquals(TEST_IMAGE_DATA_URL, imagePart["image_url"]!!.jsonPrimitive.content)
                assertEquals("original", imagePart["detail"]!!.jsonPrimitive.content)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun wrapsToollessJunieResponsesTextAsCommandProtocolInsteadOfToolCall() {
        TestUpstream(responseBody = COMPLETED_RESPONSE_STREAM_WITH_TEXT).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/responses"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"stream\":false,\"store\":false," +
                                    "\"input\":[" +
                                    "{\"type\":\"message\",\"role\":\"system\",\"content\":" +
                                    "\"You are Junie, working with a special interface.\"}," +
                                    "{\"type\":\"message\",\"role\":\"user\",\"content\":\"test\"}]}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                val outputItem = parseObject(response.body())["output"]!!.jsonArray[0].jsonObject
                assertEquals("message", outputItem["type"]!!.jsonPrimitive.content)
                val text = outputItem["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
                assertTrue(text.contains("<THOUGHT>pong</THOUGHT>"))
                assertTrue(text.contains("<COMMAND>submit</COMMAND>"))
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun doesNotRewriteChatsThatMerelyMentionJunie() {
        TestUpstream(responseBody = COMPLETED_RESPONSE_STREAM_WITH_TEXT).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/chat/completions"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"messages\":[" +
                                    "{\"role\":\"system\",\"content\":\"You are a helpful assistant.\"}," +
                                    "{\"role\":\"user\",\"content\":\"How does the junie special interface " +
                                    "with <COMMAND> and previous_step work?\"}]}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                val message = parseObject(response.body())["choices"]!!.jsonArray[0]
                    .jsonObject["message"]!!.jsonObject
                assertEquals("pong", message["content"]!!.jsonPrimitive.content)
                assertFalse("tool_calls" in message)
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun truncatesChatTextAfterFirstStopSequence() {
        TestUpstream(responseBody = COMPLETED_RESPONSE_STREAM_WITH_COMMAND_AND_TRAILING_TEXT).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/chat/completions"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"stop\":[\"</COMMAND>\"],\"messages\":[" +
                                    "{\"role\":\"system\",\"content\":\"You are Junie. Use the special interface.\"}," +
                                    "{\"role\":\"user\",\"content\":\"test\"}]}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                val choice = parseObject(response.body())["choices"]!!.jsonArray[0].jsonObject
                val content = choice["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
                // Standard semantics: the fired stop sequence is excluded from the content and
                // reported via finish_details; Junie re-appends it client-side (STOP_AFTER).
                assertEquals("<THOUGHT>listing</THOUGHT>\n<COMMAND>ls", content)
                val finishDetails = choice["finish_details"]!!.jsonObject
                assertEquals("stop", finishDetails["type"]!!.jsonPrimitive.content)
                assertEquals("</COMMAND>", finishDetails["stop"]!!.jsonPrimitive.content)
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun mapsUsageLimitErrorsToInsufficientQuota() {
        TestUpstream(
            responseBody = "{\"detail\":\"You've hit your usage limit. usage_limit_reached\"}",
            responseContentType = "application/json",
            responseStatus = 404,
        ).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/chat/completions"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(429, response.statusCode())
                val error = parseObject(response.body())["error"]!!.jsonObject
                assertEquals("insufficient_quota", error["type"]!!.jsonPrimitive.content)
                assertEquals("insufficient_quota", error["code"]!!.jsonPrimitive.content)
                assertTrue(error["message"]!!.jsonPrimitive.content.contains("usage limit"))
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun returns401WhenOpenAiLoginIsMissing() {
        TestUpstream().use { upstream ->
            val proxy = newProxy(upstream.baseUri, accessToken = null)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/chat/completions"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(401, response.statusCode())
                val error = parseObject(response.body())["error"]!!.jsonObject
                assertEquals("authentication_error", error["type"]!!.jsonPrimitive.content)
                assertTrue(error["message"]!!.jsonPrimitive.content.contains("login required"))
                assertNull(upstream.requests.poll(200, TimeUnit.MILLISECONDS))
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun forwardsChatResponseFormatAsTextFormat() {
        TestUpstream(responseBody = COMPLETED_RESPONSE_STREAM_WITH_TEXT).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/chat/completions"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]," +
                                    "\"response_format\":{\"type\":\"json_schema\",\"json_schema\":{" +
                                    "\"name\":\"answer\",\"strict\":true,\"schema\":{\"type\":\"object\"," +
                                    "\"properties\":{\"result\":{\"type\":\"integer\"}}}}}}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                val format = parseObject(request.body)["text"]!!.jsonObject["format"]!!.jsonObject
                assertEquals("json_schema", format["type"]!!.jsonPrimitive.content)
                assertEquals("answer", format["name"]!!.jsonPrimitive.content)
                assertEquals(true, format["strict"]!!.jsonPrimitive.boolean)
                assertEquals("object", format["schema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun servesLiteLlmStyleRoutesWithoutV1Prefix() {
        TestUpstream(responseBody = COMPLETED_RESPONSE_STREAM_WITH_TEXT).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()

                val models = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/models"))
                        .header("Authorization", "Bearer local-key")
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(200, models.statusCode())
                assertEquals("list", parseObject(models.body())["object"]!!.jsonPrimitive.content)

                val chat = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/chat/completions"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(200, chat.statusCode())
                assertEquals("chat.completion", parseObject(chat.body())["object"]!!.jsonPrimitive.content)

                // Health probes are unauthenticated, like LiteLLM's.
                val liveliness = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/health/liveliness"))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(200, liveliness.statusCode())
                assertTrue(liveliness.body().contains("alive"))
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun retriesUpstreamFailuresWhenLiteLlmRetryHeaderIsSet() {
        TestUpstream(responseBody = COMPLETED_RESPONSE_STREAM_WITH_TEXT, failFirstRequests = 1).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/chat/completions"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .header("x-litellm-num-retries", "1")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                assertEquals("gpt-5.5", response.headers().firstValue("x-litellm-model-id").orElse(null))
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun doesNotRetryAmbiguousServerErrors() {
        TestUpstream(
            responseBody = COMPLETED_RESPONSE_STREAM_WITH_TEXT,
            failFirstRequests = 1,
            failStatus = 500,
        ).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/chat/completions"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .header("x-litellm-num-retries", "3")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                // A bare 500 may be post-metering, so it is surfaced rather than retried.
                assertEquals(500, response.statusCode())
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertNull(upstream.requests.poll(500, TimeUnit.MILLISECONDS))
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun emitsStreamingUsageChunkOnlyWhenClientOptsIn() {
        TestUpstream(responseBody = RESPONSE_STREAM_WITH_TEXT_DELTAS).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                fun streamBody(streamOptions: String) = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/chat/completions"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"stream\":true,$streamOptions" +
                                    "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                ).body()

                val withoutUsage = streamBody("")
                assertFalse(withoutUsage.contains("\"prompt_tokens\""))

                val withUsage = streamBody("\"stream_options\":{\"include_usage\":true},")
                assertTrue(withUsage.contains("\"prompt_tokens\""))
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun refreshesTokenAndRetriesOnceAfterUpstream401() {
        TestUpstream(
            responseBody = COMPLETED_RESPONSE_STREAM_WITH_TEXT,
            failFirstRequests = 1,
            failStatus = 401,
        ).use { upstream ->
            val tokens = ArrayDeque(listOf("stale-token", "fresh-token"))
            var refreshedWith: String? = null
            val port = freePort()
            val proxy = TestProxy(
                port = port,
                server = OpenAiProxyServer(
                    port = port,
                    localApiKeyProvider = { "local-key" },
                    accessTokenProvider = { tokens.first() },
                    accountIdProvider = { "account-1" },
                    tokenRefresher = { stale ->
                        refreshedWith = stale
                        if (tokens.size > 1) tokens.removeFirst()
                        tokens.first()
                    },
                    upstreamBaseUri = upstream.baseUri,
                ),
            )
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/chat/completions"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                // The proxy signalled refresh with the rejected token, then retried.
                assertEquals("stale-token", refreshedWith)
                val first = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("Bearer stale-token", first.firstHeader("Authorization"))
                val retry = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("Bearer fresh-token", retry.firstHeader("Authorization"))
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun surfaces401WithoutRetryWhenNoTokenRefresherIsWired() {
        TestUpstream(
            responseBody = COMPLETED_RESPONSE_STREAM_WITH_TEXT,
            failFirstRequests = 1,
            failStatus = 401,
        ).use { upstream ->
            val proxy = newProxy(upstream.baseUri)
            try {
                proxy.start()
                val response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${proxy.port}/v1/chat/completions"))
                        .header("Authorization", "Bearer local-key")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"gpt-5.5\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                // The default refresher cannot produce a new token, so the 401 is surfaced
                // immediately instead of burning a doomed retry with the same token.
                assertEquals(401, response.statusCode())
                assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
                assertNull(upstream.requests.poll(500, TimeUnit.MILLISECONDS))
            } finally {
                proxy.stop()
            }
        }
    }

    private fun newProxy(
        upstreamBaseUri: URI,
        accessToken: String? = "codex-token",
    ): TestProxy {
        val port = freePort()
        return TestProxy(
            port = port,
            server = OpenAiProxyServer(
                port = port,
                localApiKeyProvider = { "local-key" },
                accessTokenProvider = { accessToken },
                accountIdProvider = { "account-1" },
                upstreamBaseUri = upstreamBaseUri,
                codexVersionProvider = { TEST_CODEX_VERSION },
            ),
        )
    }

    private data class TestProxy(val port: Int, val server: OpenAiProxyServer) {
        fun start() = server.start()
        fun stop() = server.stop()
    }

    private class TestUpstream(
        private val responseBody: String = "{\"ok\":true}",
        private val responseContentType: String = "text/event-stream",
        private val responseHeaders: Map<String, String> = emptyMap(),
        private val responseStatus: Int = 200,
        private val failFirstRequests: Int = 0,
        private val failStatus: Int = 503,
    ) : AutoCloseable {
        val requests = LinkedBlockingQueue<CapturedRequest>()
        private val requestCount = java.util.concurrent.atomic.AtomicInteger(0)
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val baseUri: URI

        init {
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                requests += CapturedRequest(
                    method = exchange.requestMethod,
                    path = exchange.requestURI.rawPath,
                    query = exchange.requestURI.rawQuery,
                    headers = exchange.requestHeaders.mapValues { it.value.toList() },
                    body = body,
                )
                val failing = requestCount.incrementAndGet() <= failFirstRequests
                val response = (if (failing) "{\"detail\":\"transient upstream failure\"}" else responseBody)
                    .toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", if (failing) "application/json" else responseContentType)
                if (!failing) {
                    responseHeaders.forEach { (name, value) -> exchange.responseHeaders.set(name, value) }
                }
                exchange.sendResponseHeaders(if (failing) failStatus else responseStatus, response.size.toLong())
                exchange.responseBody.use { output -> output.write(response) }
            }
            server.start()
            baseUri = URI.create("http://127.0.0.1:${server.address.port}/backend-api/codex")
        }

        override fun close() {
            server.stop(0)
        }
    }

    private data class CapturedRequest(
        val method: String,
        val path: String,
        val query: String?,
        val headers: Map<String, List<String>>,
        val body: String,
    ) {
        fun firstHeader(name: String): String? {
            return headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
        }
    }

    private fun parseObject(value: String) = JsonSupport.json.parseToJsonElement(value).jsonObject

    private fun freePort(): Int {
        return ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
    }

    private companion object {
        val httpClient: HttpClient = HttpClient.newHttpClient()
        const val TEST_CODEX_VERSION = "0.999.0"
        const val TEST_IMAGE_DATA_URL =
            "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII="
        const val COMPLETED_RESPONSE_STREAM_WITH_TEXT = "event: response.completed\n" +
            "data: {\"type\":\"response.completed\",\"response\":{" +
            "\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"completed\"," +
            "\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"pong\"}]}]," +
            "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n"
        const val COMPLETED_RESPONSE_STREAM_WITH_COMMAND_AND_TRAILING_TEXT = "event: response.completed\n" +
            "data: {\"type\":\"response.completed\",\"response\":{" +
            "\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"completed\"," +
            "\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"" +
            "<THOUGHT>listing</THOUGHT>\\n<COMMAND>ls</COMMAND>ignored trailing chatter" +
            "\"}]}],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n"
        const val COMPLETED_RESPONSE_STREAM_WITH_FUNCTION_CALL = "event: response.completed\n" +
            "data: {\"type\":\"response.completed\",\"response\":{" +
            "\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"completed\"," +
            "\"output\":[{\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"create\"," +
            "\"arguments\":\"{\\\"path\\\":\\\"issue.md\\\"}\"}]," +
            "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n"

        // Mirrors the real Codex backend with store=false: function call items arrive only
        // via output_item events, argument deltas reference the item id ("fc_..."), and the
        // final response.completed event carries an empty output array.
        const val FUNCTION_CALL_STREAM_WITH_EMPTY_COMPLETED_OUTPUT = "event: response.output_item.added\n" +
            "data: {\"type\":\"response.output_item.added\",\"item\":{\"id\":\"fc_1\"," +
            "\"type\":\"function_call\",\"status\":\"in_progress\",\"arguments\":\"\"," +
            "\"call_id\":\"call_1\",\"name\":\"create\"}}\n\n" +
            "event: response.function_call_arguments.delta\n" +
            "data: {\"type\":\"response.function_call_arguments.delta\"," +
            "\"delta\":\"{\\\"path\\\":\\\"issue.md\\\"}\",\"item_id\":\"fc_1\"}\n\n" +
            "event: response.output_item.done\n" +
            "data: {\"type\":\"response.output_item.done\",\"item\":{\"id\":\"fc_1\"," +
            "\"type\":\"function_call\",\"status\":\"completed\"," +
            "\"arguments\":\"{\\\"path\\\":\\\"issue.md\\\"}\",\"call_id\":\"call_1\",\"name\":\"create\"}}\n\n" +
            "event: response.completed\n" +
            "data: {\"type\":\"response.completed\",\"response\":{" +
            "\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"completed\",\"output\":[]," +
            "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n" +
            "data: [DONE]\n\n"
        const val COMPLETED_RESPONSE_STREAM_WITH_JUNIE_PROTOCOL_TEXT = "event: response.completed\n" +
            "data: {\"type\":\"response.completed\",\"response\":{" +
            "\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"completed\"," +
            "\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"" +
            "<UPDATE><PREVIOUS_STEP>Finished proxy response formatting.</PREVIOUS_STEP>" +
            "<PLAN>Internal analysis should not be shown.</PLAN>" +
            "<NEXT_STEP>Retry Junie in the IDE.</NEXT_STEP></UPDATE>" +
            "\"}]}],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n"
        const val RESPONSE_STREAM_WITH_TEXT_DELTAS = "event: response.output_text.delta\n" +
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"po\"}\n\n" +
            "event: response.output_text.delta\n" +
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"ng\"}\n\n" +
            COMPLETED_RESPONSE_STREAM_WITH_TEXT
        const val HOSTED_IMAGE_GENERATION_RESPONSE_STREAM = "event: response.output_item.done\n" +
            "data: {\"type\":\"response.output_item.done\",\"item\":{\"id\":\"ig_1\"," +
            "\"type\":\"image_generation_call\",\"status\":\"generating\"," +
            "\"revised_prompt\":\"Draw a cat\",\"result\":\"cG5n\"}}\n\n" +
            COMPLETED_RESPONSE_STREAM_WITH_TEXT

        const val JUNIE_UPDATE_TEXT = "<UPDATE>\\n<PREVIOUS_STEP>\\nChecked the SDK state.\\n</PREVIOUS_STEP>\\n" +
            "<PLAN>\\n1. Check current state ✓\\n2. Install latest SDK *\\n</PLAN>\\n" +
            "<NEXT_STEP>\\nInstall the latest SDK.\\n</NEXT_STEP>\\n</UPDATE>"
        const val COMPLETED_RESPONSE_STREAM_WITH_UPDATE_TEXT_AND_FUNCTION_CALL = "event: response.completed\n" +
            "data: {\"type\":\"response.completed\",\"response\":{" +
            "\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"completed\"," +
            "\"output\":[" +
            "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"" + JUNIE_UPDATE_TEXT + "\"}]}," +
            "{\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"bash\"," +
            "\"arguments\":\"{\\\"command\\\":\\\"pebble sdk install latest\\\"}\"}]," +
            "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n"

        // Text deltas split mid-tag plus a function call delivered via output_item events;
        // the completed event carries an empty output array like the real backend with store=false.
        const val JUNIE_UPDATE_STREAM_WITH_FUNCTION_CALL = "event: response.output_text.delta\n" +
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"<UPDATE>\\n<PREVIOUS_STEP>\\nChecked the SDK state.\\n</PREVIOUS_STEP>\\n<PLA\"}\n\n" +
            "event: response.output_text.delta\n" +
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"N>\\n1. Check current state ✓\\n</PLAN>\\n<NEXT_STEP>\\nInstall the latest SDK.\\n</NEXT_STEP>\\n</UPDATE>\"}\n\n" +
            "event: response.output_item.added\n" +
            "data: {\"type\":\"response.output_item.added\",\"item\":{\"id\":\"fc_1\"," +
            "\"type\":\"function_call\",\"status\":\"in_progress\",\"arguments\":\"\"," +
            "\"call_id\":\"call_1\",\"name\":\"bash\"}}\n\n" +
            "event: response.function_call_arguments.delta\n" +
            "data: {\"type\":\"response.function_call_arguments.delta\"," +
            "\"delta\":\"{\\\"command\\\":\\\"pebble sdk install latest\\\"}\",\"item_id\":\"fc_1\"}\n\n" +
            "event: response.output_item.done\n" +
            "data: {\"type\":\"response.output_item.done\",\"item\":{\"id\":\"fc_1\"," +
            "\"type\":\"function_call\",\"status\":\"completed\"," +
            "\"arguments\":\"{\\\"command\\\":\\\"pebble sdk install latest\\\"}\",\"call_id\":\"call_1\",\"name\":\"bash\"}}\n\n" +
            "event: response.completed\n" +
            "data: {\"type\":\"response.completed\",\"response\":{" +
            "\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"completed\",\"output\":[]," +
            "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n" +
            "data: [DONE]\n\n"
    }
}
