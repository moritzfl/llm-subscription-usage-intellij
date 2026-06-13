package de.moritzf.quota.idea.mcp

import com.sun.net.httpserver.HttpServer
import de.moritzf.quota.shared.JsonSupport
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.io.TempDir

class CodexMcpClientTest {
    @Test
    fun postsWebSearchToCodexResponsesEndpointWithQuotaAuth() {
        TestUpstream(
            responseBody = sse(
                """{"type":"response.output_text.delta","delta":"search "}""",
                """{"type":"response.output_text.delta","delta":"result"}""",
                """{"type":"response.completed","response":{"id":"resp_1","web_search":{"num_requests":1},"tool_usage":{"web_search":{"num_requests":1}}}}""",
            ),
        ).use { upstream ->
            val client = newClient(upstream.baseUri)

            val response = client.webSearch("OpenAI news")

            assertFalse(response.isError)
            assertEquals("search result", parseObject(response.body)["output"]!!.jsonPrimitive.content)
            val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("POST", request.method)
            assertEquals("/backend-api/codex/responses", request.path)
            assertEquals("Bearer codex-token", request.firstHeader("Authorization"))
            assertEquals("account-1", request.firstHeader("chatgpt-account-id"))
            assertEquals(TEST_CODEX_VERSION, request.firstHeader("version"))
            assertNull(request.firstHeader("OpenAI-Beta"))
            assertEquals("text/event-stream", request.firstHeader("Accept"))

            val body = parseObject(request.body)
            assertEquals("gpt-5.5", body["model"]!!.jsonPrimitive.content)
            assertTrue(body["stream"]!!.jsonPrimitive.boolean)
            assertFalse(body["store"]!!.jsonPrimitive.boolean)
            assertEquals(
                "Search the web for: OpenAI news",
                body["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0]
                    .jsonObject["text"]!!.jsonPrimitive.content,
            )
            val tool = body["tools"]!!.jsonArray[0].jsonObject
            assertEquals("web_search", tool["type"]!!.jsonPrimitive.content)
            assertTrue(tool["external_web_access"]!!.jsonPrimitive.boolean)
            assertEquals("text", tool["search_content_types"]!!.jsonArray[0].jsonPrimitive.content)
        }
    }

    @Test
    fun postsImageGenerationToCodexResponsesEndpointWithQuotaAuth() {
        TestUpstream(
            responseBody = sse(
                """{"type":"response.output_item.done","item":{"type":"image_generation_call","id":"ig_1","status":"generating","revised_prompt":"draw a tiny robot","result":"cG5n"}}""",
                """{"type":"response.completed","response":{"id":"resp_1","tool_usage":{"image_gen":{"total_tokens":12}}}}""",
            ),
        ).use { upstream ->
            val client = newClient(upstream.baseUri)

            val response = client.imageGeneration("draw a tiny robot")

            assertFalse(response.isError)
            val responseBody = parseObject(response.body)
            assertEquals("cG5n", responseBody["data"]!!.jsonArray[0].jsonObject["b64_json"]!!.jsonPrimitive.content)
            assertEquals(
                "draw a tiny robot",
                responseBody["data"]!!.jsonArray[0].jsonObject["revised_prompt"]!!.jsonPrimitive.content,
            )
            val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("POST", request.method)
            assertEquals("/backend-api/codex/responses", request.path)
            assertEquals("Bearer codex-token", request.firstHeader("Authorization"))
            assertEquals("account-1", request.firstHeader("chatgpt-account-id"))
            assertEquals(TEST_CODEX_VERSION, request.firstHeader("version"))
            assertNull(request.firstHeader("OpenAI-Beta"))

            val body = parseObject(request.body)
            assertEquals("gpt-5.5", body["model"]!!.jsonPrimitive.content)
            assertTrue(body["stream"]!!.jsonPrimitive.boolean)
            assertEquals(
                "draw a tiny robot",
                body["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0]
                    .jsonObject["text"]!!.jsonPrimitive.content,
            )
            val tool = body["tools"]!!.jsonArray[0].jsonObject
            assertEquals("image_generation", tool["type"]!!.jsonPrimitive.content)
            assertEquals("png", tool["output_format"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun writesImageGenerationToTargetFile(@TempDir tempDir: Path) {
        TestUpstream(
            responseBody = sse(
                """{"type":"response.output_item.done","item":{"type":"image_generation_call","id":"ig_1","status":"generating","revised_prompt":"draw a tiny robot","result":"$TEST_PNG_BASE64"}}""",
                """{"type":"response.completed","response":{"id":"resp_1","tool_usage":{"image_gen":{"total_tokens":12}}}}""",
            ),
        ).use { upstream ->
            val client = newClient(upstream.baseUri)

            val response = client.imageGeneration("draw a tiny robot", "robot.png", tempDir)

            assertFalse(response.isError)
            val responseBody = parseObject(response.body)
            val targetFile = tempDir.resolve("robot.png")
            assertEquals(targetFile.toString(), responseBody["output_file"]!!.jsonPrimitive.content)
            assertEquals("png", responseBody["format"]!!.jsonPrimitive.content)
            assertTrue(responseBody["bytes"]!!.jsonPrimitive.long > 0)
            assertEquals("draw a tiny robot", responseBody["revised_prompt"]!!.jsonPrimitive.content)
            assertFalse("data" in responseBody)
            assertTrue(Files.exists(targetFile))
            val signature = Files.newInputStream(targetFile).use { it.readNBytes(8).toList() }
            assertEquals(listOf(137, 80, 78, 71, 13, 10, 26, 10), signature.map { it.toInt() and 0xff })

            val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("/backend-api/codex/responses", request.path)
        }
    }

    @Test
    fun rejectsUnsupportedImageFileFormatBeforeCallingUpstream(@TempDir tempDir: Path) {
        TestUpstream().use { upstream ->
            val client = newClient(upstream.baseUri)

            val response = client.imageGeneration("draw a tiny robot", "robot.txt", tempDir)

            assertTrue(response.isError)
            assertTrue(parseObject(response.body)["error"]!!.jsonPrimitive.content.contains("Unsupported image format 'txt'"))
            assertNull(upstream.requests.poll(500, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun reportsResponsesFailedEventAsError() {
        TestUpstream(
            responseBody = sse(
                """{"type":"response.failed","response":{"error":{"message":"usage limit reached"}}}""",
            ),
        ).use { upstream ->
            val client = newClient(upstream.baseUri)

            val response = client.webSearch("OpenAI news")

            assertTrue(response.isError)
            assertEquals("usage limit reached", parseObject(response.body)["error"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun refreshesTokenAndRetriesOnceAfterUpstream401() {
        TestUpstream(
            responseBody = sse(
                """{"type":"response.output_text.delta","delta":"search result"}""",
            ),
            failFirstRequests = 1,
            failStatus = 401,
        ).use { upstream ->
            val tokens = ArrayDeque(listOf("stale-token", "fresh-token"))
            var refreshedWith: String? = null
            val client = CodexMcpClient(
                accessTokenProvider = { tokens.first() },
                accountIdProvider = { "account-1" },
                tokenRefresher = { stale ->
                    refreshedWith = stale
                    if (tokens.size > 1) tokens.removeFirst()
                    tokens.first()
                },
                codexVersionProvider = { TEST_CODEX_VERSION },
                httpClient = httpClient,
                upstreamBaseUri = upstream.baseUri,
            )

            val response = client.webSearch("OpenAI news")

            assertFalse(response.isError)
            assertEquals("stale-token", refreshedWith)
            val first = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("Bearer stale-token", first.firstHeader("Authorization"))
            val retry = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("Bearer fresh-token", retry.firstHeader("Authorization"))
        }
    }

    @Test
    fun reportsLoginRequiredWithoutCallingUpstream() {
        TestUpstream().use { upstream ->
            val client = CodexMcpClient(
                accessTokenProvider = { null },
                accountIdProvider = { "account-1" },
                codexVersionProvider = { TEST_CODEX_VERSION },
                httpClient = httpClient,
                upstreamBaseUri = upstream.baseUri,
            )

            val response = client.webSearch("OpenAI news")

            assertTrue(response.isError)
            assertEquals(
                "OpenAI login required: log in on the OpenAI settings tab, then retry.",
                parseObject(response.body)["error"]!!.jsonPrimitive.content,
            )
            assertNull(upstream.requests.poll(500, TimeUnit.MILLISECONDS))
        }
    }

    private fun newClient(upstreamBaseUri: URI): CodexMcpClient {
        return CodexMcpClient(
            accessTokenProvider = { "codex-token" },
            accountIdProvider = { "account-1" },
            codexVersionProvider = { TEST_CODEX_VERSION },
            httpClient = httpClient,
            upstreamBaseUri = upstreamBaseUri,
        )
    }

    private class TestUpstream(
        private val responseBody: String = sse("""{"type":"response.output_text.delta","delta":"ok"}"""),
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
                    headers = exchange.requestHeaders.mapValues { it.value.toList() },
                    body = body,
                )
                val failing = requestCount.incrementAndGet() <= failFirstRequests
                val response = (if (failing) "{\"detail\":\"transient upstream failure\"}" else responseBody)
                    .toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "text/event-stream")
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
        val headers: Map<String, List<String>>,
        val body: String,
    ) {
        fun firstHeader(name: String): String? {
            return headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
        }
    }

    private fun parseObject(value: String) = JsonSupport.json.parseToJsonElement(value).jsonObject

    private companion object {
        const val TEST_CODEX_VERSION = "0.999.0"
        const val TEST_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII="
        val httpClient: HttpClient = HttpClient.newHttpClient()

        fun sse(vararg payloads: String): String {
            return payloads.joinToString(separator = "\n\n", postfix = "\n\n") { "data: $it" }
        }
    }
}
